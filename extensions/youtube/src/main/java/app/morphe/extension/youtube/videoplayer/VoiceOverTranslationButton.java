/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.VotBottomSheet;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class VoiceOverTranslationButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    @Nullable
    private static WeakReference<ImageView> overlayButtonRef;

    @Nullable
    private static ValueAnimator pulseAnimator;

    /** Injection point. */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS || !Settings.VOT_ENABLED.get()) return;

            VoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);

            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    "morphe_yt_vot_bold",
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VotBottomSheet.show(view.getContext());
                        return true;
                    });
            overlayButtonRef = button != null ? new WeakReference<>(button) : null;
            refreshActivatedState();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /** Injection point. */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;

            VoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_vot_button",
                    null,
                    "morphe_yt_vot",
                    Settings.VOT_ENABLED,
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VotBottomSheet.show(view.getContext());
                        return true;
                    });
            refreshActivatedState();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static void refreshActivatedState() {
        Utils.verifyOnMainThread();
        try {
            final boolean isEnabled = VoiceOverTranslationPatch.isSessionEnabled();
            final boolean isLoading = VoiceOverTranslationPatch.isLoading();
            final int targetAlpha = isEnabled ? 255 : 128;

            if (isEnabled && isLoading) {
                if (pulseAnimator == null) {
                    pulseAnimator = ValueAnimator.ofInt(70, 255);
                    pulseAnimator.setDuration(500);
                    pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
                    pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                    pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
                    pulseAnimator.addUpdateListener(anim -> {
                        int val = (int) anim.getAnimatedValue();
                        WeakReference<ImageView> ref = overlayButtonRef;
                        ImageView iv = ref != null ? ref.get() : null;
                        if (iv != null) {
                            iv.setImageAlpha(val);
                        }
                        LegacyPlayerControlButton leg = legacy;
                        if (leg != null) {
                            leg.setImageAlpha(val);
                        }
                    });
                    pulseAnimator.start();
                }
            } else {
                if (pulseAnimator != null) {
                    pulseAnimator.cancel();
                    pulseAnimator = null;
                }
                WeakReference<ImageView> ref = overlayButtonRef;
                ImageView iv = ref != null ? ref.get() : null;
                if (iv != null) {
                    iv.setImageAlpha(targetAlpha);
                }
                LegacyPlayerControlButton leg = legacy;
                if (leg != null) {
                    leg.setImageAlpha(targetAlpha);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "refreshActivatedState failure", ex);
        }
    }
}
