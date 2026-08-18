package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Handles playing the translated audio track from Yandex synchronously with the video.
 *
 * <p>If the stream fails (preparation error, mid-playback death, or an early
 * completion reported by MediaPlayer), the same URL is re-prepared automatically
 * a bounded number of times before giving up. Giving up clears the source, which
 * lets {@link VoiceOverTranslationPatch#maybeRetryYandexLoad()} re-request the
 * translation from the API.
 */
public class YandexAudioEngine {
    
    public static final YandexAudioEngine INSTANCE = new YandexAudioEngine();
    
    private static final int MAX_PREPARE_RETRIES = 3;
    
    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private String currentUrl;
    private float currentVolume = 1.0f;
    private float currentSpeed = 1.0f;
    private int prepareRetryCount;
    private boolean retryScheduled;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private YandexAudioEngine() {}
    
    public void prepare(String audioUrl) {
        Utils.runOnMainThread(() -> {
            handler.removeCallbacksAndMessages(null);
            retryScheduled = false;
            prepareRetryCount = 0;
            prepareInternal(audioUrl);
        });
    }

    /**
     * @return true while an audio source is attached (prepared, preparing, or awaiting
     * a retry). False once the engine gave up or was stopped.
     */
    public boolean hasAudioSession() {
        return currentUrl != null;
    }

    private void prepareInternal(String audioUrl) {
        Utils.verifyOnMainThread();
        if (mediaPlayer != null) {
            stopInternal();
        }

        currentUrl = audioUrl;
        isPrepared = false;
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        );

        try {
            mediaPlayer.setDataSource(audioUrl);
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                Logger.printDebug(() -> "Yandex audio prepared");
                // Apply saved volume/speed
                setVolume(currentVolume);
                setSpeed(currentSpeed);
                if (app.morphe.extension.youtube.shared.VideoState.getCurrent() == app.morphe.extension.youtube.shared.VideoState.PLAYING) {
                    play(app.morphe.extension.youtube.patches.VideoInformation.getVideoTime());
                } else {
                    pause();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Logger.printException(() -> "Yandex MediaPlayer error: " + what + " " + extra);
                isPrepared = false;
                schedulePrepareRetry();
                return true;
            });
            // MediaPlayer occasionally reports completion while the stream is still
            // playing (short HLS segment or a transient network stall). A clip that
            // ended well before its reported duration almost always means the source
            // died, so re-prepare instead of going silent for the rest of the video.
            mediaPlayer.setOnCompletionListener(mp -> {
                int duration = 0;
                int position = 0;
                try {
                    duration = mp.getDuration();
                    position = mp.getCurrentPosition();
                } catch (Exception ignored) {}
                if (duration > 0 && position < duration - 1_500) {
                    final int pos = position;
                    final int dur = duration;
                    Logger.printDebug(() -> "Yandex audio ended early (position=" + pos
                            + " duration=" + dur + "), retrying");
                    schedulePrepareRetry();
                } else {
                    Logger.printDebug(() -> "Yandex audio finished");
                    stop();
                }
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Logger.printException(() -> "Failed to set Yandex audio source", e);
            schedulePrepareRetry();
        }
    }

    private void schedulePrepareRetry() {
        Utils.verifyOnMainThread();
        final String url = currentUrl;
        if (url == null || retryScheduled) {
            return;
        }
        if (prepareRetryCount >= MAX_PREPARE_RETRIES) {
            Logger.printException(() -> "Yandex audio retries exhausted for: " + url);
            Utils.showToastShort("Не удалось воспроизвести озвучку Яндекса");
            stop();
            return;
        }
        retryScheduled = true;
        final long delayMs = 1_000L * (prepareRetryCount + 1);
        prepareRetryCount++;
        final int attempt = prepareRetryCount;
        handler.postDelayed(() -> {
            retryScheduled = false;
            if (url.equals(currentUrl)) {
                Logger.printDebug(() -> "Retrying Yandex audio preparation (attempt " + attempt + ")");
                prepareInternal(url);
            }
        }, delayMs);
    }
    
    public void play(long videoPositionMs) {
        Utils.runOnMainThread(() -> {
            if (app.morphe.extension.youtube.shared.VideoState.getCurrent() != app.morphe.extension.youtube.shared.VideoState.PLAYING) {
                Logger.printDebug(() -> "Skipping Yandex play because video is not PLAYING");
                return;
            }
            if (mediaPlayer != null && isPrepared) {
                try {
                    // Sync position if it differs by more than 500ms
                    if (Math.abs(mediaPlayer.getCurrentPosition() - videoPositionMs) > 500) {
                        mediaPlayer.seekTo((int) videoPositionMs);
                    }
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                    VotOriginalVolumePatch.setAudioMultiplier(app.morphe.extension.youtube.settings.Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
                } catch (Exception e) {
                    Logger.printException(() -> "Failed to start Yandex audio", e);
                }
            }
        });
    }
    
    public void pause() {
        Utils.runOnMainThread(() -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                VotOriginalVolumePatch.clearAudioMultiplier();
            }
        });
    }
    
    public void seekTo(long positionMs) {
        Utils.runOnMainThread(() -> {
            if (mediaPlayer != null && isPrepared) {
                mediaPlayer.seekTo((int) positionMs);
            }
        });
    }
    
    public void stop() {
        Utils.runOnMainThread(() -> {
            handler.removeCallbacksAndMessages(null);
            retryScheduled = false;
            prepareRetryCount = 0;
            stopInternal();
        });
    }

    private void stopInternal() {
        Utils.verifyOnMainThread();
        isPrepared = false;
        currentUrl = null;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (Exception ignored) {}
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {}
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception e) {
                Logger.printException(() -> "Failed to stop Yandex audio", e);
            }
            mediaPlayer = null;
            VotOriginalVolumePatch.clearAudioMultiplier();
        }
    }
    
    public void setVolume(float volume) {
        currentVolume = volume;
        Utils.runOnMainThread(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(volume, volume);
            }
        });
    }
    
    public void setSpeed(float speed) {
        currentSpeed = Math.max(speed, 0.1f);
        Utils.runOnMainThread(() -> {
            if (mediaPlayer != null && isPrepared) {
                try {
                    android.media.PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(currentSpeed);
                    mediaPlayer.setPlaybackParams(params);
                } catch (Exception e) {
                    Logger.printException(() -> "Failed to set playback speed", e);
                }
            }
        });
    }
    
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
}
