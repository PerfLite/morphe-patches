package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Handles playing the translated audio track from Yandex synchronously with the video.
 */
public class YandexAudioEngine {
    
    public static final YandexAudioEngine INSTANCE = new YandexAudioEngine();
    
    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private String currentUrl;
    private float currentVolume = 1.0f;
    private float currentSpeed = 1.0f;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private YandexAudioEngine() {}
    
    public void prepare(String audioUrl) {
        Utils.runOnMainThread(() -> {
            if (mediaPlayer != null) {
                stop();
            }
            
            currentUrl = audioUrl;
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
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Logger.printException(() -> "Yandex MediaPlayer error: " + what + " " + extra);
                    isPrepared = false;
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Logger.printException(() -> "Failed to set Yandex audio source", e);
            }
        });
    }
    
    public void play(long videoPositionMs) {
        Utils.runOnMainThread(() -> {
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
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception e) {
                    Logger.printException(() -> "Failed to stop Yandex audio", e);
                }
                mediaPlayer = null;
                isPrepared = false;
                currentUrl = null;
                VotOriginalVolumePatch.clearAudioMultiplier();
            }
        });
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
                    PlaybackParams params = mediaPlayer.getPlaybackParams();
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
