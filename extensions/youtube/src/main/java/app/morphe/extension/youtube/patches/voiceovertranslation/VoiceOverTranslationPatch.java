/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_OPENROUTER;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationResponse;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Orchestrator for the voice-over translation feature: reacts to video time/state changes,
 * fetches and translates captions, prefetches TTS audio, and dispatches each segment to
 * either the Edge or System TTS engine.
 *
 * <p>Collaborators:
 * <ul>
 *   <li>{@link TranscriptFetcher} / {@link TranscriptTranslator} - caption pipeline</li>
 *   <li>{@link TtsPrefetcher} - background synthesis into {@link TtsCache}</li>
 *   <li>{@link TtsEngine} - Edge TTS WebSocket + MediaPlayer playback</li>
 *   <li>{@link VotOriginalVolumePatch} - ducks the original audio while TTS speaks</li>
 * </ul>
 *
 * <p>State is touched only on the main thread; the few cross-thread reads use volatile fields.
 */
@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class VoiceOverTranslationPatch {

    public static final Setting.ImportExportCallback VOT_IMPORT_EXPORT_CALLBACK = new Setting.ImportExportCallback() {
        @Override
        public void settingsImported(@Nullable Activity context) {}

        @Override
        public void settingsExported(@Nullable Activity context) {
            showExportWarningIfNeeded(context);
        }
    };

    private static void showExportWarningIfNeeded(@Nullable Activity activity) {
        Utils.verifyOnMainThread();
        if (activity == null) return;
        if (Settings.VOT_OPENROUTER_API_KEY.get().trim().isEmpty()) return;
        if (Settings.VOT_HIDE_EXPORT_WARNING.get()) return;
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                activity,
                null,
                str("morphe_vot_export_api_key_warning"),
                null,
                null,
                () -> {},
                null,
                str("morphe_vot_do_not_show_again"),
                () -> Settings.VOT_HIDE_EXPORT_WARNING.save(true),
                true
        );
        Utils.showDialog(activity, dialogPair.first, false, null);
    }

    public static class MyMemoryServiceAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY);
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.VOT_TRANSLATION_SERVICE);
        }
    }

    public static class OpenRouterServiceAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_OPENROUTER);
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.VOT_TRANSLATION_SERVICE);
        }
    }

    // Tick-over-tick time jump above this triggers seek handling.
    private static final long SEEK_JUMP_THRESHOLD_MS = 2_900;

    // Minimum time into a segment to justify seeking within the audio instead of
    // playing from the start. Prevents tiny pops on small adjustments.
    private static final long SEEK_INTO_THRESHOLD_MS = 1_000;

    // Floor on the calculated speech rate; never slow speech below natural speed.
    private static final float MIN_SPEECH_RATE = 1.0f;

    public static final String TTS_ENGINE_SYSTEM = "system";
    private static final String YANDEX_SERVICE = "yandex";
    private static final String VOT_ID_PREFIX = "vot_";
    private static final String VOT_TEST_ID_PREFIX = "vot_test_";
    private static final String TEST_VIDEO_ID = "test";
    private static final int TEST_SEGMENT_INDEX = -1;
    private static final long TEST_PREFETCH_WAIT = 500;

    static volatile long lastVideoTimeMs;
    // Tracks the latest known position even when paused, unlike lastVideoTimeMs which only
    // updates during PLAYING. Used by translate() to pick the right initial batch when a video
    // starts mid-position (PAUSED setVideoTime calls arrive before newVideoLoaded and before
    // lastVideoTimeMs is ever set for the new video).
    static volatile long videoPositionHint;
    // Estimated video timestamp when the currently-playing TTS audio finishes.
    // Duck is held until this time so TTS that extends into the gap before the
    // next segment does not prematurely restore the original audio volume.
    private static long ttsEndVideoTimeMs;
    // Deduplicates lookahead wake-ups so a tight tick loop does not flood the main looper.
    private static long scheduledCheckForSegmentStartMs = -1;
    // Tracks slot-fit rate and the playback speed in effect when the active utterance
    // started, so a mid-utterance video speed change can be re-applied to MediaPlayer.
    // lastAppliedPlaybackSpeed = -1 means nothing is playing.
    private static float currentTtsBaseRate = 1.0f;
    private static float lastAppliedPlaybackSpeed = -1f;

    private static List<TranscriptSegment> segments = new ArrayList<>();
    // Volatile so background threads can read the active segment without
    // taking a lock. Writes still happen only on the main thread.
    private static volatile int lastSpokenIndex = -1;

    /** @return Index of the segment whose audio is mid-playback, or -1. Safe off-main-thread. */
    static int getLastSpokenIndex() {
        return lastSpokenIndex;
    }
    private static String currentVideoId = "";
    private static boolean isLoading;
    private static boolean sessionEnabled = Settings.VOT_SESSION_ENABLED.get();
    private static boolean wasExplicitSeek;
    private static volatile boolean httpErrorDialogShownThisVideo;

    private static Runnable onStateChangeCallback;

    private static TextToSpeech tts;
    private static boolean ttsReady;

    private static boolean isTestSpeaking;
    private static long currentTestId;
    private static long currentPreloadId;
    private static String lastTestVoiceId = "";

    private static final TtsEngine ttsEngine = TtsEngine.INSTANCE;

    static {
        PlayerType.getOnChange().addObserver(playerType -> {
            if (!playerType.isMaximizedOrFullscreen()
                    && playerType != PlayerType.WATCH_WHILE_MINIMIZED
                    && playerType != PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE
                    && playerType != PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED) {
                Logger.printDebug(() -> "Stopping TTS for player type: " + playerType);
                stopTts();
                YandexAudioEngine.INSTANCE.stop();
                if (playerType.isNoneOrHidden() || playerType == PlayerType.NONE || playerType == PlayerType.HIDDEN) {
                    currentVideoId = "";
                    segments = new ArrayList<>();
                    TtsPrefetcher.clear();
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        ShortsPlayerState.getOnChange().addObserver(isOpen -> {
            if (isOpen) {
                Logger.printDebug(() -> "Shorts opened, stopping translation audio");
                stopTts();
                YandexAudioEngine.INSTANCE.stop();
                currentVideoId = "";
                segments = new ArrayList<>();
                TtsPrefetcher.clear();
            }
            return kotlin.Unit.INSTANCE;
        });

        VideoState.getOnChange().addObserver(state -> {
            // Only take the Yandex-audio path while an audio stream is actually attached.
            // Otherwise (first request failed or the translation fell back to TTS) fall
            // through to the generic TTS handling below.
            if (YANDEX_SERVICE.equals(Settings.VOT_TRANSLATION_SERVICE.get())
                    && YandexAudioEngine.INSTANCE.hasAudioSession()) {
                if (state == VideoState.PAUSED) {
                    YandexAudioEngine.INSTANCE.pause();
                } else if (state == VideoState.PLAYING) {
                    PlayerType currentType = PlayerType.getCurrent();
                    if (Settings.VOT_ENABLED.get() && sessionEnabled && !ShortsPlayerState.isOpen()
                            && (currentType.isMaximizedOrFullscreen() || currentType == PlayerType.WATCH_WHILE_MINIMIZED || currentType == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE)) {
                        YandexAudioEngine.INSTANCE.play(VideoInformation.getVideoTime());
                    } else {
                        YandexAudioEngine.INSTANCE.pause();
                    }
                } else if (state == VideoState.ENDED) {
                    YandexAudioEngine.INSTANCE.stop();
                }
                return kotlin.Unit.INSTANCE;
            }

            if (state == VideoState.PAUSED) {
                Logger.printDebug(() -> "Stopping TTS for video state PAUSED");
                stopTts();
            } else if (state == VideoState.PLAYING) {
                PlayerType currentType = PlayerType.getCurrent();
                if (!Settings.VOT_ENABLED.get() || !sessionEnabled || ShortsPlayerState.isOpen() || currentType.isNoneOrHidden()) {
                    stopTts();
                } else {
                    ttsEngine.resume();
                }
            } else if (state == VideoState.ENDED) {
                Logger.printDebug(() -> "Stopping TTS for video state ENDED");
                stopTts();
                TtsPrefetcher.clear();
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        // Always reset so seek detection fires correctly on the first videoTimeChanged
        // and so the first segment at the new position is spoken even when the same
        // video is reopened at a different timestamp (e.g. chapter links, continue watching).
        lastVideoTimeMs = 0;
        lastSpokenIndex = -1;
        wasExplicitSeek = false;
        if (videoId.equals(currentVideoId)) return;

        Logger.printDebug(() -> "preloadTranslations newVideoLoaded");
        TranscriptTranslator.requestAbort();
        stopTts();
        YandexAudioEngine.INSTANCE.stop();
        currentVideoId = videoId;
        segments = new ArrayList<>();
        httpErrorDialogShownThisVideo = false;

        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) return;
        if (ShortsPlayerState.isOpen() || PlayerType.getCurrent().isNoneOrHidden() || PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL) {
            Logger.printDebug(() -> "Skipping translation in newVideoLoaded for Shorts/hidden player: " + videoId);
            return;
        }
        TtsPrefetcher.updateVideo(videoId, segments);
        loadTranscript(videoId);

        // Open the Edge socket in parallel so the first synthesis doesn't pay handshake cost.
        if (!Settings.VOT_USE_NATIVE_TTS.get()) {
            Utils.runOnBackgroundThread(() -> {
                try {
                    ttsEngine.warmConnection();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Edge warm-up failed: " + ex);
                }
            });
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long timeMs) {
        PlayerType currentPlayerType = PlayerType.getCurrent();
        if (!Settings.VOT_ENABLED.get() || !sessionEnabled
                || (!currentPlayerType.isMaximizedOrFullscreen()
                    && currentPlayerType != PlayerType.WATCH_WHILE_MINIMIZED
                    && currentPlayerType != PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE)) {
            VotOriginalVolumePatch.clearAudioMultiplier();
            YandexAudioEngine.INSTANCE.pause();
            return; // Feature or session disabled or non-playing player type (Shorts, etc).
        }
        Utils.verifyOnMainThread();

        propagatePlaybackSpeedIfChanged();
        VideoState state = VideoState.getCurrent();
        // Capture position before the PAUSED early return so translate() can pick the right
        // initial batch even when the first setVideoTime ticks arrive before play begins.
        videoPositionHint = timeMs;
        // Video state can be null until the overlay is activated the first time.
        if (state != null && state != VideoState.PLAYING) {
            Logger.printDebug(() -> "Ignoring TTS for video state: " + state);
            YandexAudioEngine.INSTANCE.pause();
            return; // paused, ended, or loading
        }
        if (YANDEX_SERVICE.equals(Settings.VOT_TRANSLATION_SERVICE.get())) {
            YandexAudioEngine.INSTANCE.play(timeMs);
            return;
        }

        final long prevVideoTimeMs = lastVideoTimeMs;
        lastVideoTimeMs = timeMs;

        if (segments.isEmpty()) return;

        if (prevVideoTimeMs > 0) {
            final long timeSinceLastUpdate = Math.abs(timeMs - prevVideoTimeMs);
            // Scale by playback speed so at high speeds a normal tick (which spans a
            // larger in-video gap) isn't mistaken for a user seek.
            final long jumpThreshold = (long) (SEEK_JUMP_THRESHOLD_MS
                    * Math.max(1.0f, VideoInformation.getPlaybackSpeed()));
            if (timeSinceLastUpdate > jumpThreshold) {
                // Small jumps within the same segment are handled by speak()'s startTime logic.
                Logger.printDebug(() -> "videoTimeChanged jump detected: " + timeSinceLastUpdate + "ms");
                wasExplicitSeek = true;
                stopTtsPreservingMultiplier();
                lastSpokenIndex = -1;
                // Re-target translation at the new position so a seek into an untranslated region
                // is translated next instead of waiting for the sequential dispatch to reach it.
                TranscriptTranslator.onSeek(timeMs);
            }
        }

        // Amount of time to look ahead for the next segment to schedule it.
        final long lookaheadMs = 900;
        // Small delay added to scheduled segments to ensure the video time has definitely
        // reached the segment start time before the check runs.
        final long schedulingDelayMs = 10;

        for (int i = 0, size = segments.size(); i < size; i++) {
            TranscriptSegment seg = segments.get(i);
            final long segPlaybackStartMs = seg.playbackStartMs;
            if (timeMs >= segPlaybackStartMs) {
                if (timeMs < seg.playbackEndMs) {
                    if (i != lastSpokenIndex) {
                        if (TranscriptTranslator.isAwaitingTranslationAt(i, seg.startMs, seg.text)) {
                            final int segIdx = i;
                            Logger.printDebug(() -> "Waiting for translation at segment: " + segIdx);
                            break;
                        }
                        // isAwaitingTranslationAt returns false once a batch is marked done, even if
                        // translation failed and the segment kept its source-language text. Check lang
                        // so a permanently untranslated segment is never spoken.
                        if (TranscriptFetcher.isSpokenLanguageDifferent(resolveTargetLang(), seg.lang)) {
                            final int segIdx = i;
                            Logger.printDebug(() -> "Skipping untranslated segment: " + segIdx);
                            break;
                        }
                        if (!ttsEngine.isSpeaking() || wasExplicitSeek) {
                            lastSpokenIndex = i;
                            Logger.printDebug(() -> "Found segment: " + lastSpokenIndex
                                    + " videoTime: " + timeMs);
                            speak(seg, i);
                        }
                    }
                    break;
                }
            } else if (i > lastSpokenIndex && segPlaybackStartMs <= timeMs + lookaheadMs) {
                // Next segment starts between now and the next update to this method.
                // Schedule a call to recheck TTS playback when the segment will start.
                if (segPlaybackStartMs != scheduledCheckForSegmentStartMs) {
                    final float speed = Math.max(0.1f, VideoInformation.getPlaybackSpeed());
                    final long delayMs = (long) ((segPlaybackStartMs - timeMs + schedulingDelayMs) / speed);
                    Logger.printDebug(() -> "Scheduling next segment check in " + delayMs + "ms");
                    scheduledCheckForSegmentStartMs = segPlaybackStartMs;
                    Utils.runOnMainThreadDelayed(() -> {
                        scheduledCheckForSegmentStartMs = -1;
                        videoTimeChanged(VideoInformation.getVideoTime());
                    }, delayMs);
                }
                break;
            }
        }
        // ttsEndVideoTimeMs keeps the duck alive while TTS speaks into the gap before the next
        // segment, preventing a brief volume flicker mid-utterance.
        if (ttsEngine.isSpeaking() || isTestSpeaking || timeMs < ttsEndVideoTimeMs) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        } else {
            VotOriginalVolumePatch.clearAudioMultiplier();
        }
    }

    /** @return true when VoT is enabled, the session is on, and a transcript is loaded. */
    public static boolean isTranslationActive() {
        Utils.verifyOnMainThread();
        return Settings.VOT_ENABLED.get() && sessionEnabled
                && (!segments.isEmpty() || YandexAudioEngine.INSTANCE.hasAudioSession());
    }

    /** @return Per-session enabled flag (toggleable via the player button) - not the global setting. */
    public static boolean isSessionEnabled() {
        return sessionEnabled;
    }

    public static boolean isLoading() {
        return isLoading;
    }

    /** Flips the session enabled flag and either stops TTS or kicks off transcript loading. */
    public static void toggleTranslation() {
        Utils.verifyOnMainThread();
        sessionEnabled = !sessionEnabled;
        Settings.VOT_SESSION_ENABLED.save(sessionEnabled);
        if (!sessionEnabled) {
            stopTts();
            YandexAudioEngine.INSTANCE.stop();
            lastSpokenIndex = -1;
        } else {
            if (VideoState.getCurrent() != VideoState.PLAYING) {
                stopTts();
                YandexAudioEngine.INSTANCE.pause();
                lastSpokenIndex = -1;
            } else {
                videoTimeChanged(VideoInformation.getVideoTime());
            }
            if (!currentVideoId.isEmpty() && segments.isEmpty() && !YandexAudioEngine.INSTANCE.hasAudioSession() && !isLoading) {
                loadTranscript(currentVideoId);
            }
        }
        notifyStateChanged();
    }

    /** Stops any in-progress TTS without changing session state. */
    public static void interruptSpeech() {
        Utils.verifyOnMainThread();
        stopTts();
        YandexAudioEngine.INSTANCE.pause();
    }

    /**
     * Resets every segment's playback window to its original caption timing and asks the
     * prefetcher to re-evaluate. Used by the bottom-sheet "reset" action.
     */
    public static void resetPlaybackState() {
        Utils.verifyOnMainThread();
        for (TranscriptSegment seg : segments) {
            seg.playbackStartMs = seg.startMs;
            seg.playbackEndMs = seg.endMs;
            seg.durationMs = -1;
        }
        TtsPrefetcher.triggerRescan();
    }

    /** Applies the current voice volume setting to the active playback. */
    public static void updatePlaybackVolume() {
        Utils.verifyOnMainThread();
        float vol = Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f;
        ttsEngine.setVolume(vol);
        YandexAudioEngine.INSTANCE.setVolume(vol);
    }

    /** Re-applies the ducking multiplier so a Settings change takes effect immediately. */
    public static void updateOriginalAudioMultiplier() {
        Utils.verifyOnMainThread();
        if (ttsEngine.isSpeaking() || isTestSpeaking || YandexAudioEngine.INSTANCE.isPlaying()) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        }
    }

    /** Discards the current transcript and TTS state and re-fetches for the current video. */
    public static void reloadTranscript() {
        Utils.verifyOnMainThread();
        if (currentVideoId.isEmpty()) return;
        stopTts();
        YandexAudioEngine.INSTANCE.stop();
        segments = new ArrayList<>();
        lastSpokenIndex = -1;
        // Without this, in-flight onUpdate callbacks for the old language would restore
        // stale segments after we cleared them.
        TranscriptTranslator.requestAbort();
        if (!isLoading) {
            loadTranscript(currentVideoId);
        }
    }

    /** Registers a callback fired whenever toggle/load state changes (used by the player button UI). */
    public static void setOnTranslationStateChangeCallback(Runnable callback) {
        Utils.verifyOnMainThread();
        onStateChangeCallback = callback;
    }

    private static void notifyStateChanged() {
        Logger.printDebug(() -> "notifyStateChanged");
        Utils.verifyOnMainThread();
        if (onStateChangeCallback != null) onStateChangeCallback.run();
    }

    private static void loadTranscript(String videoId) {
        Logger.printDebug(() -> "loadTranscript: " + videoId);
        Utils.verifyOnMainThread();
        if (isLoading) return;
        isLoading = true;
        notifyStateChanged();
        final String loadLang = resolveTargetLang();
        final String loadService = Settings.VOT_TRANSLATION_SERVICE.get();

        Utils.runOnBackgroundThread(() -> {
            try {
                if (YANDEX_SERVICE.equals(loadService)) {
                    if (loadYandexAudio(videoId, loadLang)) {
                        return;
                    }
                }

                // Later translation batches arrive asynchronously; swap the list in only
                // while the same video is still playing. Timings and size are identical
                // across updates, so lastSpokenIndex stays valid.
                final BooleanSupplier fetchCancelled = () ->
                        !videoId.equals(currentVideoId) || !sessionEnabled
                                || ShortsPlayerState.isOpen() || PlayerType.getCurrent().isNoneOrHidden()
                                || VideoState.getCurrent() == VideoState.ENDED;

                List<TranscriptSegment> fetched;
                try {
                    fetched = TranscriptFetcher.fetch(videoId, segmentUpdater(videoId, loadLang), fetchCancelled);
                } catch (Exception ex) {
                    logError(() -> "Transcript fetch failed", ex);
                    fetched = new ArrayList<>();
                }

                // Caption tracks are flaky (bot checks, missing ASR, expired poToken).
                // A single empty result must not kill the whole video - wait a bit and
                // try once more before giving up.
                if (fetched.isEmpty() && !fetchCancelled.getAsBoolean()) {
                    Logger.printDebug(() -> "No transcript segments for " + videoId + ", retrying once");
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignored) {}
                    if (!fetchCancelled.getAsBoolean()) {
                        try {
                            fetched = TranscriptFetcher.fetch(videoId, segmentUpdater(videoId, loadLang), fetchCancelled);
                        } catch (Exception ex) {
                            logError(() -> "Transcript fetch retry failed", ex);
                        }
                    }
                }

                // If the video is already in the target language (e.g. Russian video and
                // target is Russian), do NOT speak TTS! Only trust lastSourceLang when
                // segments were actually fetched; otherwise a stale language left by the
                // previous video would silently skip translation for this one too.
                if (!fetched.isEmpty()
                        && !TranscriptFetcher.isSpokenLanguageDifferent(loadLang, TranscriptFetcher.lastSourceLang)) {
                    Logger.printDebug(() -> "Video is already in target language (" + TranscriptFetcher.lastSourceLang + "), skipping TTS");
                    Utils.runOnMainThread(() -> {
                        segments = new ArrayList<>();
                        notifyStateChanged();
                    });
                    return;
                }

                final List<TranscriptSegment> fetchedFinal = fetched;
                Utils.runOnMainThread(() -> {
                    if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())) {
                        // With sequential batch execution, cancelCheck.get() ensures every
                        // onUpdate fires before translate() returns, so segments is already
                        // fully translated by the time we arrive here. Only fall back to the
                        // batch-0 snapshot (fetched) if onUpdate never ran (single batch or
                        // no translation needed).
                        if (segments.isEmpty()) segments = fetchedFinal;
                        TtsPrefetcher.updateVideo(videoId, segments);
                        Logger.printDebug(() -> "Loaded: " + fetchedFinal.size() + " segments for :" + videoId);
                        notifyStateChanged();
                    }
                });
            } catch (Exception ex) {
                logError(() -> "Transcript fetch failed", ex);
            } finally {
                Utils.runOnMainThread(() -> {
                    isLoading = false;
                    notifyStateChanged();
                    // Restart if the video, language, or translation provider changed while this fetch was in flight.
                    if (!currentVideoId.isEmpty() && Settings.VOT_ENABLED.get()
                            && (!currentVideoId.equals(videoId)
                            || !loadLang.equals(resolveTargetLang())
                            || !loadService.equals(Settings.VOT_TRANSLATION_SERVICE.get()))) {
                        loadTranscript(currentVideoId);
                    }
                });
            }
        });
    }

    /** Publishes translated segment batches to playback while the same video is active. */
    private static Consumer<List<TranscriptSegment>> segmentUpdater(String videoId, String loadLang) {
        return updated -> {
            Utils.verifyOnMainThread();
            if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())
                    && sessionEnabled && Settings.VOT_ENABLED.get()
                    && !ShortsPlayerState.isOpen() && !PlayerType.getCurrent().isNoneOrHidden()) {
                // If the segment we last started speaking had its text replaced
                // by a freshly-arrived translation, stop and let videoTimeChanged
                // re-speak it with the translated text on the next tick.
                if (lastSpokenIndex >= 0
                        && lastSpokenIndex < segments.size()
                        && lastSpokenIndex < updated.size() && !segments.get(lastSpokenIndex).text
                        .equals(updated.get(lastSpokenIndex).text)) {
                    stopTts();
                }
                segments = updated;
                notifyStateChanged();
            }
        };
    }

    /**
     * Requests the ready-made Yandex voice-over for the current video, polls while the
     * backend is generating it, and hands the resulting stream URL to
     * {@link YandexAudioEngine}. Runs on the background thread started by
     * {@link #loadTranscript(String)}.
     *
     * <p>Matches the {@code @vot.js/core} YandexProvider semantics:
     * FINISHED/PART_CONTENT with a URL means success, WAITING/LONG_WAITING mean keep
     * polling, AUDIO_REQUESTED means the backend expects the player-side audio and the
     * fail-audio-js fallback must be sent, FAILED means give up.
     *
     * @return true when the Yandex engine took over playback and the TTS fallback must
     * not run. Note the audio is attached asynchronously - if preparation eventually
     * fails, {@link #maybeRetryYandexLoad()} re-requests it during playback.
     */
    private static boolean loadYandexAudio(String videoId, String loadLang) {
        String videoUrl = "https://youtu.be/" + videoId;
        double duration = VideoInformation.getVideoLength() / 1000.0;
        boolean preferLively = Settings.VOT_YANDEX_LIVELY_VOICE.get();
        boolean livelyDisabled = false;
        VideoTranslationResponse response = null;
        boolean audioRequestedHandled = false;

        final String originalLang = "auto";

        VideoTranslationResponse first = YandexTranslationService.translate(
                videoUrl, originalLang, loadLang, duration, preferLively);

        // Check if Yandex indicates the video is already in the target language
        if (first != null && !first.getAllowToTranslateVideo() && first.getResponseMessage() != null
                && (first.getResponseMessage().toLowerCase().contains("совпадает")
                    || first.getResponseMessage().toLowerCase().contains("на том же языке"))) {
            Logger.printDebug(() -> "Video is already in target language according to Yandex, skipping translation");
            Utils.runOnMainThread(() -> {
                segments = new ArrayList<>();
                notifyStateChanged();
            });
            return true;
        }

        // Lively voice unavailable for this video pair: the backend either refuses with
        // FAILED or answers with a message that mentions the regular voice
        // ("обычная озвучка") - retry once without it, mirroring @vot.js behavior.
        if (preferLively && first != null && !isWaiting(first) && !isFinishedLike(first)) {
            String firstMsg = first.getResponseMessage();
            boolean messageMentionsRegularVoice = firstMsg != null
                    && firstMsg.toLowerCase().contains("обычная озвучка");
            boolean refused = first.getResponseStatusValue() == 0 /* FAILED */;
            if (messageMentionsRegularVoice || refused) {
                Logger.printDebug(() -> "Lively voice not available for this video, falling back to standard voice");
                response = YandexTranslationService.translate(videoUrl, originalLang, loadLang, duration, false);
                if (messageMentionsRegularVoice) {
                    Utils.showToastShort("Живой голос недоступен для этого видео, включена стандартная озвучка");
                }
                livelyDisabled = true;
            }
        }
        if (response == null) response = first;

        int retries = 0;
        while (retries < 60) {
            if (!yandexStillRelevant(videoId)) {
                Logger.printDebug(() -> "Aborting Yandex translation polling: video changed or session inactive");
                break;
            }

            final int statusVal = response != null ? response.getResponseStatusValue() : -1;

            if (response != null && response.getTranslationUrl() != null
                    && !response.getTranslationUrl().isEmpty()) {
                // FINISHED or PART_CONTENT with an attached audio URL.
                final String audioUrl = response.getTranslationUrl();
                Utils.runOnMainThread(() -> {
                    if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())
                            && sessionEnabled && Settings.VOT_ENABLED.get()
                            && !PlayerType.getCurrent().isNoneOrHidden() && !ShortsPlayerState.isOpen()) {
                        YandexAudioEngine.INSTANCE.prepare(audioUrl);
                        segments = new ArrayList<>(); // clear segments so TTS doesn't play
                        notifyStateChanged();
                    }
                });
                return true;
            } else if (statusVal == 2 /* WAITING */ || statusVal == 3 /* LONG_WAITING */) {
                Logger.printDebug(() -> "Yandex translation is work in progress. Waiting...");
                if (retries == 0) {
                    int remaining = response.getRemainingTime();
                    String msg = response.getResponseMessage();
                    if (msg != null && !msg.trim().isEmpty()) {
                        Utils.showToastShort("⏳ " + msg);
                    } else if (remaining > 0) {
                        if (remaining >= 60) {
                            Utils.showToastShort("⏳ Яндекс генерирует озвучку (~" + (remaining / 60) + " мин)");
                        } else {
                            Utils.showToastShort("⏳ Яндекс генерирует озвучку (~" + remaining + " сек)");
                        }
                    } else {
                        Utils.showToastShort("⏳ Яндекс генерирует озвучку...");
                    }
                }
                long waitMs = retries == 0 && response.getRemainingTime() > 0
                        ? Math.min(response.getRemainingTime() * 1000L, 30_000L)
                        : 7000L;
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    break;
                }
                if (!yandexStillRelevant(videoId)) {
                    Logger.printDebug(() -> "Aborting Yandex polling after sleep: video changed or session inactive");
                    break;
                }
                retries++;
                response = YandexTranslationService.translate(
                        videoUrl, originalLang, resolveTargetLang(), duration, livelyDisabled ? false : Settings.VOT_YANDEX_LIVELY_VOICE.get());
            } else if (statusVal == 6 /* AUDIO_REQUESTED */) {
                // Backend needs the source audio. The browser extension downloads it in-page;
                // the Android client cannot, so mirror @vot.js's fail-audio-js path once and
                // let the backend generate the audio from the URL on its side.
                if (!audioRequestedHandled) {
                    audioRequestedHandled = true;
                    Logger.printDebug(() -> "Yandex requested the audio (AUDIO_REQUESTED), sending fail-audio-js");
                    if (YandexTranslationService.reportAudioUnavailable(videoUrl, response.getTranslationId())) {
                        response = YandexTranslationService.translate(videoUrl, originalLang, loadLang, duration, false);
                        retries++;
                        continue;
                    }
                }
                Logger.printDebug(() -> "Yandex audio translation not ready (status=" + statusVal + "), falling back to subtitles TTS");
                break;
            } else {
                // null response / FAILED / SESSION_REQUIRED / unknown - no point polling.
                Logger.printDebug(() -> "Yandex audio translation not ready (status=" + statusVal + "), falling back to subtitles TTS");
                break;
            }
        }
        Logger.printDebug(() -> "Yandex audio not attached for: " + videoId);
        return false;
    }

    private static boolean isFinishedLike(VideoTranslationResponse response) {
        return response != null && response.getTranslationUrl() != null
                && !response.getTranslationUrl().isEmpty();
    }

    private static boolean isWaiting(VideoTranslationResponse response) {
        if (response == null) return false;
        int status = response.getResponseStatusValue();
        return status == 2 || status == 3;
    }

    private static boolean yandexStillRelevant(String videoId) {
        return videoId.equals(currentVideoId) && sessionEnabled && Settings.VOT_ENABLED.get()
                && !PlayerType.getCurrent().isNoneOrHidden() && !ShortsPlayerState.isOpen();
    }

    /** Lazily creates the System TTS instance and wires its completion listener. Idempotent. */
    public static void ensureTts() {
        Utils.verifyOnMainThread();
        if (tts != null) return;
        Logger.printDebug(() -> "ensureTts creating tts");

        tts = new TextToSpeech(Utils.getContext(), status -> Utils.runOnMainThreadNowOrLater(() -> {
            if (status != TextToSpeech.SUCCESS) {
                Logger.printDebug(() -> "TTS initialization failed: " + status);
                return;
            }
            updateTtsLanguage();

            // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE plays TTS on a dedicated audio usage
            // so its volume is controlled independently of YouTube's media stream.
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    Utils.runOnMainThreadNowOrLater(() -> {
                        try {
                            if (utteranceId == null) return;
                            if (utteranceId.startsWith(VOT_TEST_ID_PREFIX)) {
                                String suffix = utteranceId.substring(VOT_TEST_ID_PREFIX.length());
                                String[] parts = suffix.split("_");
                                final long tId = Long.parseLong(parts[0]);
                                final long pId = Long.parseLong(parts[1]);
                                ttsEngine.clearBusy(pId);
                                if (tId == currentTestId) isTestSpeaking = false;
                            } else if (utteranceId.startsWith(VOT_ID_PREFIX)) {
                                long id = Long.parseLong(utteranceId.substring(VOT_ID_PREFIX.length()));
                                if (id == ttsEngine.getPlaybackId()) {
                                    ttsEngine.clearBusy(id);
                                    triggerNextSegmentCheck();
                                }
                            }
                        } catch (Exception ex) {
                            logError(() -> "Utterance listener onDone failure", ex);
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    onDone(utteranceId);
                }
            });

            ttsReady = true;
        }));
    }

    private static void updateTtsLanguage() {
        Utils.verifyOnMainThread();
        if (tts == null) return;
        Locale locale = Locale.forLanguageTag(resolveTargetLang());
        final int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH);
        }
    }

    private static void speak(TranscriptSegment seg, int index) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "Speak: " + seg);
        String lang = resolveTargetLang();
        final float volume = Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f;

        String voice = resolveVoice(lang);
        if (voice == null) return;

        final long speakFromMs = Math.max(lastVideoTimeMs, seg.playbackStartMs);
        final long availableMs = seg.playbackEndMs - speakFromMs;

        // Exact if cached, otherwise estimated from char count.
        final long speechDurationMs = getSpeechDurationMs(seg, index, voice, lang);

        // Calculate if we should seek into the audio (e.g. after a short seek within segment).
        long startTimeMs = 0;
        if (wasExplicitSeek) {
            final long timeIntoSegment = lastVideoTimeMs - seg.playbackStartMs;
            if (timeIntoSegment > SEEK_INTO_THRESHOLD_MS) {
                // Approximate audio position assuming natural speed. The TTS clip is usually
                // shorter than the video segment, so clamp to its length to avoid seeking past
                // the end.
                startTimeMs = Math.min(timeIntoSegment, speechDurationMs);
            }
            final long startTimeMsFinal = startTimeMs;
            Logger.printDebug(() -> "Explicit seek resume. timeIntoSegment: " + timeIntoSegment
                    + "ms, startTimeMs: " + startTimeMsFinal + "ms");
            // Reset the flag so future segments at normal playback start from the beginning.
            wasExplicitSeek = false;
        }

        // Rate must be based on the audio that will actually play, not the full clip.
        final long remainingSpeechMs = Math.max(0, speechDurationMs - startTimeMs);
        final float rate = calculateSpeechRate(remainingSpeechMs, availableMs);
        ttsEndVideoTimeMs = speakFromMs + (long) (remainingSpeechMs / rate);
        currentTtsBaseRate = rate;
        lastAppliedPlaybackSpeed = VideoInformation.getPlaybackSpeed();

        if (TTS_ENGINE_SYSTEM.equals(voice)) {
            ensureTts();
            if (!ttsReady) {
                Logger.printDebug(() -> "Native TTS not ready, skipping segment");
                return;
            }
            updateTtsLanguage();
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            tts.setSpeechRate(rate * VideoInformation.getPlaybackSpeed());
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            final long id = ttsEngine.markBusy();
            // System TTS doesn't support seekTo, so it will always play from the start.
            tts.speak(seg.text, TextToSpeech.QUEUE_FLUSH, params, VOT_ID_PREFIX + id);
            return;
        }

        VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        // Multiply by playback speed so TTS keeps pace with non-1.0x video.
        final float playbackRate = rate * VideoInformation.getPlaybackSpeed();
        byte[] cached = TtsCache.get(currentVideoId, index, voice, lang, seg.text);
        if (cached != null) {
            final long playbackId = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, playbackRate, startTimeMs, playbackId,
                    VoiceOverTranslationPatch::triggerNextSegmentCheck);
            return;
        }

        // Synthesize at natural speed so the result can be cached and reused at any rate;
        // playback rate is applied by MediaPlayer instead.
        final long playbackId = ttsEngine.markBusy();
        final String videoIdSnapshot = currentVideoId;
        final long startTimeMsSnapshot = startTimeMs;
        Utils.runOnBackgroundThread(() -> {
            byte[] data;
            try {
                data = ttsEngine.prefetch(seg.text, voice, lang);
            } catch (Exception ex) {
                logError(() -> "On-demand synthesis failed for segment " + index, ex);
                Utils.runOnMainThread(VoiceOverTranslationPatch::triggerNextSegmentCheck);
                return;
            }
            if (data.length > 0) {
                TtsCache.put(videoIdSnapshot, index, voice, lang, seg.text, data);
            }
            final byte[] finalData = data;
            Utils.runOnMainThread(() -> {
                if (finalData.length > 0 && playbackId == ttsEngine.getPlaybackId()) {
                    if (VideoState.getCurrent() != VideoState.PLAYING) {
                        Logger.printDebug(() -> "Dropping TTS playback because video is not PLAYING (state=" + VideoState.getCurrent() + ")");
                        return;
                    }
                    // Re-read playback speed in case it changed during synthesis.
                    final float playbackRateNow = rate * VideoInformation.getPlaybackSpeed();
                    ttsEngine.play(finalData, volume, playbackRateNow, startTimeMsSnapshot, playbackId,
                            VoiceOverTranslationPatch::triggerNextSegmentCheck);
                } else {
                    triggerNextSegmentCheck();
                }
            });
        });
    }

    private static void triggerNextSegmentCheck() {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (VideoState.getCurrent() == VideoState.PLAYING) {
                videoTimeChanged(VideoInformation.getVideoTime());
            }
        });
    }

    /**
     * Pushes a new MediaPlayer rate when video speed changes mid-utterance so the listener
     * doesn't wait for the current one to finish. No-op for System TTS (Android TextToSpeech
     * cannot change rate in-flight; the next utterance picks up the new value).
     */
    private static void propagatePlaybackSpeedIfChanged() {
        if (lastAppliedPlaybackSpeed < 0) return;
        final float current = VideoInformation.getPlaybackSpeed();
        if (current == lastAppliedPlaybackSpeed) return;
        lastAppliedPlaybackSpeed = current;
        if (YANDEX_SERVICE.equals(Settings.VOT_TRANSLATION_SERVICE.get())) {
            YandexAudioEngine.INSTANCE.setSpeed(current);
            return;
        }
        ttsEngine.setPlaybackRate(currentTtsBaseRate * current);
    }

    /**
     * Returns a speech rate multiplier that fits {@code speechDurationMs} into {@code availableMs}.
     * Never slows below normal speed and is capped by the user-configured max rate.
     */
    private static float calculateSpeechRate(long speechDurationMs, long availableMs) {
        final float maxRate = Settings.VOT_MAX_SPEECH_RATE.get() / 10.0f;
        if (availableMs <= 0) return maxRate;
        return Math.max(MIN_SPEECH_RATE, Math.min(maxRate, speechDurationMs / (float) availableMs));
    }

    private static long getSpeechDurationMs(TranscriptSegment seg, int index, String voice, String lang) {
        long duration = seg.durationMs;
        if (duration <= 0) {
            duration = TtsCache.getDuration(currentVideoId, index, voice, lang, seg.text);
            if (duration > 0) seg.durationMs = duration;
        }
        return duration > 0 ? duration : (long) seg.text.length() * TtsEngine.ESTIMATED_MS_PER_CHAR;
    }

    /**
     * Estimates natural speech duration from character count and delegates to
     * {@link #calculateSpeechRate(long, long)}. Used when exact duration is not yet known.
     */
    private static float calculateSpeechRate(String text, long availableMs) {
        return calculateSpeechRate((long) text.length() * TtsEngine.ESTIMATED_MS_PER_CHAR, availableMs);
    }

    /**
     * Called when the video position is programmatically seeked (e.g. SponsorBlock).
     * Stops any in-progress TTS immediately, regardless of how short the jump was,
     * so stale audio never plays over the new video position.
     */
    public static void onVideoSeeked() {
        Logger.printDebug(() -> "onVideoSeeked");
        Utils.verifyOnMainThread();
        wasExplicitSeek = true;

        // If no segment is mid-playback there is nothing to stop or preserve. This also
        // skips the redundant work when videoTimeChanged's jump detection just handled
        // this same seek (it resets lastSpokenIndex to -1).
        if (lastSpokenIndex < 0) return;

        // Check if the seek was within the current segment. If so, let videoTimeChanged
        // handle the restart/seek-into logic to avoid a jarring stop and restart.
        boolean insideSameSegment = false;
        if (lastSpokenIndex < segments.size()) {
            TranscriptSegment seg = segments.get(lastSpokenIndex);
            if (lastVideoTimeMs >= seg.playbackStartMs && lastVideoTimeMs < seg.playbackEndMs) {
                insideSameSegment = true;
            }
        }

        if (!insideSameSegment) {
            stopTtsPreservingMultiplier();
            lastSpokenIndex = -1;
        }
    }

    /** @return Short sample sentence in the current target language; used by voice preview. */
    public static String getTestString() {
        Locale locale = Locale.forLanguageTag(resolveTargetLang());
        return ResourceUtils.getStringByLocale("morphe_vot_tts_sample", locale);
    }

    /**
     * Synthesizes and plays a short test phrase with the given voice.
     * If the engine is already speaking the same voice, stops it.
     */
    static void testSpeak(String voiceId) {
        Logger.printDebug(() -> "testSpeak: " + voiceId);
        Utils.verifyOnMainThread();

        final boolean wasSameVoice = isTestSpeaking && voiceId.equals(lastTestVoiceId);
        stopTts();
        if (wasSameVoice) return;

        final long testId = ++currentTestId;
        isTestSpeaking = true;
        lastTestVoiceId = voiceId;

        final float volume = Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f;

        if (TTS_ENGINE_SYSTEM.equals(voiceId)) {
            ensureTts();
            if (!ttsReady) {
                isTestSpeaking = false;
                return;
            }
            updateTtsLanguage();
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            tts.setSpeechRate(1.0f);
            final long pId = ttsEngine.markBusy();
            tts.speak(getTestString(), TextToSpeech.QUEUE_FLUSH, params,
                    VOT_TEST_ID_PREFIX + testId + "_" + pId);
            return;
        }

        final String lang = resolveTargetLang();
        byte[] cached = TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voiceId, lang, getTestString());
        if (cached != null) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            final long id = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, id, () -> updateIsTestSpeaking(testId));
            return;
        }

        VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        ttsEngine.speak(getTestString(), voiceId, resolveTargetLang(), volume, () -> updateIsTestSpeaking(testId));
    }

    private static void updateIsTestSpeaking(long testId) {
        Utils.verifyOnMainThread();
        if (testId == currentTestId) isTestSpeaking = false;
    }

    /**
     * Preloads test phrases for all Edge TTS voices in the current target language.
     */
    static void preloadTestVoices() {
        Utils.verifyOnMainThread();
        final long preloadId = ++currentPreloadId;

        String lang = resolveTargetLang();
        List<VoiceCatalog.Voice> voices = VoiceCatalog.getVoicesForLang(lang);
        if (voices == null || voices.isEmpty()) return;

        Utils.runOnBackgroundThread(() -> {
            String testString = getTestString();
            for (VoiceCatalog.Voice voice : voices) {
                if (preloadId != currentPreloadId) {
                    Logger.printDebug(() -> "Aborting stale preload request: " + preloadId);
                    return;
                }

                if (TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString) != null) {
                    continue;
                }

                byte[] diskData = TtsCache.getTestSampleFromDisk(voice.id, lang);
                if (diskData != null) {
                    TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString, diskData);
                    continue;
                }

                try {
                    Logger.printDebug(() -> "Prefetching test phrase for: " + voice.id);
                    byte[] data = ttsEngine.prefetch(testString, voice.id, lang);
                    if (data.length > 0) {
                        TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString, data);
                        TtsCache.putTestSampleToDisk(voice.id, lang, data);
                    }
                    Thread.sleep(TEST_PREFETCH_WAIT);

                } catch (Exception ex) {
                    logError(() -> "preloadTestVoices failure: " + voice, ex);
                    return;
                }
            }
        });
    }

    private static void stopTts() {
        stopTtsInternal();
        VotOriginalVolumePatch.clearAudioMultiplier();
    }

    /**
     * Stops TTS while keeping the current ducking multiplier. Seek handlers use this so the
     * old AudioTrack (still draining buffered samples) is not briefly pushed to 100% between
     * the multiplier clear and the next speak() reapplying it - audible as a click.
     */
    private static void stopTtsPreservingMultiplier() {
        stopTtsInternal();
    }

    private static void stopTtsInternal() {
        Logger.printDebug(() -> "stopTts");
        isTestSpeaking = false;
        ttsEngine.stop();
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {}
        }
        YandexAudioEngine.INSTANCE.stop();
        lastSpokenIndex = -1;
        ttsEndVideoTimeMs = 0;
        scheduledCheckForSegmentStartMs = -1;
        currentTtsBaseRate = 1.0f;
        lastAppliedPlaybackSpeed = -1f;
    }

    /**
     * @return BCP-47 language code(pt-BR, pt-PT, en-US, etc).
     */
    static String resolveTargetLang() {
        return Settings.VOT_CAPTION_LANGUAGE.isSetToDefault()
                ? Locale.getDefault().toLanguageTag()
                : Settings.VOT_CAPTION_LANGUAGE.get();
    }

    /**
     * @param lang ISO 639 (pt) or BCP 47 (pt-BR).
     */
    private static String resolveVoice(String lang) {
        return Settings.VOT_USE_NATIVE_TTS.get()
                ? TTS_ENGINE_SYSTEM
                : VoiceCatalog.resolve(lang, Settings.VOT_TTS_VOICE_TYPE.get());
    }

    static void notifyHttpError(int statusCode) {
        if (statusCode < 400 || statusCode >= 500) return;
        if (!Settings.VOT_SHOW_HTTP_ERROR_DIALOG.get()) return;
        if (httpErrorDialogShownThisVideo) return;
        httpErrorDialogShownThisVideo = true;
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            showHttpErrorDialog(activity, statusCode);
        });
    }

    static void notifyOpenRouterError(int httpCode, String errorBody) {
        if (httpErrorDialogShownThisVideo) return;
        httpErrorDialogShownThisVideo = true;
        if (TranscriptTranslator.isOpenRouterCreditsError(httpCode, errorBody)) {
            Utils.runOnMainThread(() -> {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                showOpenRouterCreditsDialog(activity);
            });
        } else {
            Utils.showToastLong(str("morphe_vot_openrouter_error", httpCode));
        }
    }

    private static void showOpenRouterCreditsDialog(Activity activity) {
        Utils.verifyOnMainThread();
        try {
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    activity,
                    str("morphe_vot_openrouter_credits_title"),
                    str("morphe_vot_openrouter_credits_message"),
                    null,
                    null,
                    () -> {},
                    null,
                    str("morphe_vot_openrouter_open_website"),
                    () -> {
                        try {
                            activity.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/credits"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ex) {
                            logError(() -> "Failed to open openrouter.ai", ex);
                        }
                    },
                    true
            );
            pair.first.show();
        } catch (Exception ex) {
            logError(() -> "showOpenRouterCreditsDialog failure", ex);
        }
    }

    static void notifyMyMemoryError(int responseStatus, @Nullable String details) {
        if (httpErrorDialogShownThisVideo) return;
        httpErrorDialogShownThisVideo = true;
        if (TranscriptTranslator.isMyMemoryQuotaError(responseStatus, details)) {
            final String nextAvailableAt = formatNextAvailableClockTime(
                    TranscriptTranslator.parseMyMemoryNextAvailableMinutes(details));
            Utils.runOnMainThread(() -> {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                showMyMemoryQuotaDialog(activity, nextAvailableAt);
            });
        } else {
            Utils.showToastLong(str("morphe_vot_mymemory_error", responseStatus));
        }
    }

    /**
     * Converts a wait duration to the wall-clock time the quota will reset, formatted as
     * {@code HH:mm} in the device locale. Returns null when the duration is unknown so the
     * dialog can omit the "next available at" line.
     */
    @Nullable
    private static String formatNextAvailableClockTime(@Nullable Long waitMinutes) {
        if (waitMinutes == null) return null;
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, waitMinutes.intValue());
        return String.format(Locale.getDefault(), "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static void showMyMemoryQuotaDialog(Activity activity, @Nullable String nextAvailableAt) {
        Utils.verifyOnMainThread();
        try {
            // Show a different body depending on whether the user already gets the 10x
            // limit via an email - otherwise we'd suggest adding what's already there.
            final boolean emailSet = !Settings.VOT_MYMEMORY_EMAIL.get().trim().isEmpty();
            String message = emailSet
                    ? str("morphe_vot_mymemory_quota_message_with_email")
                    : str("morphe_vot_mymemory_quota_message_no_email");
            if (nextAvailableAt != null) {
                message = str("morphe_vot_mymemory_quota_next_available", nextAvailableAt)
                        + "\n\n" + message;
            }
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    activity,
                    str("morphe_vot_mymemory_quota_title"),
                    message,
                    null,
                    null,
                    () -> {},
                    null,
                    null,
                    null,
                    true
            );
            pair.first.show();
        } catch (Exception ex) {
            logError(() -> "showMyMemoryQuotaDialog failure", ex);
        }
    }

    private static void showHttpErrorDialog(Activity activity, int statusCode) {
        Utils.verifyOnMainThread();
        try {
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    activity,
                    str("morphe_vot_http_error_title"),
                    str("morphe_vot_http_error_message", statusCode),
                    null,
                    null,
                    () -> { },
                    null,
                    str("morphe_vot_do_not_show_again"),
                    () -> Settings.VOT_SHOW_HTTP_ERROR_DIALOG.save(false),
                    true
            );
            pair.first.show();
        } catch (Exception ex) {
            logError(() -> "showHttpErrorDialog failure", ex);
        }
    }

    public static void fetchOpenRouterModelCost(String model, Consumer<Float> onResult) {
        TranscriptTranslator.fetchOpenRouterModelCost(model, onResult);
    }

    public static String formatOpenRouterCostPerHundredHours(float cost) {
        if (cost == 0) {
            return str("morphe_vot_cost_free");
        }

        String costString;
        if (cost < 0.001f) {
            costString = "< $0.001";
        } else {
            String format;
            if (cost < 0.01f) {
                format = "$%.3f";
            } else {
                format = "$%.2f";
            }
            costString = String.format(Locale.US, format, cost);
        }

        return str("morphe_vot_cost_per_hour", costString);
    }

    static void logError(Logger.LogMessage message, @Nullable Exception ex) {
        if (DEBUG.get()) Logger.printException(message, ex);
        else Logger.printInfo(message, ex);
    }
}
