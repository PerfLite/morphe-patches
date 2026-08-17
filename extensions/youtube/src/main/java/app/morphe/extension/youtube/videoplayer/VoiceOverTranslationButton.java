/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
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

    private static void applyLoadingOrActiveState(ImageView view, boolean isEnabled, boolean isLoading) {
        if (view == null) return;
        if (isEnabled && isLoading) {
            if (view.getAnimation() == null) {
                view.setImageAlpha(255);
                AlphaAnimation pulse = new AlphaAnimation(0.25f, 1.0f);
                pulse.setDuration(450);
                pulse.setRepeatMode(Animation.REVERSE);
                pulse.setRepeatCount(Animation.INFINITE);
                pulse.setInterpolator(new AccelerateDecelerateInterpolator());
                view.startAnimation(pulse);
            }
        } else {
            if (view.getAnimation() != null) {
                view.clearAnimation();
            }
            view.setImageAlpha(isEnabled ? 255 : 128);
        }
    }

    private static void refreshActivatedState() {
        Utils.verifyOnMainThread();
        try {
            final boolean isEnabled = VoiceOverTranslationPatch.isSessionEnabled();
            final boolean isLoading = VoiceOverTranslationPatch.isLoading();
            final int alpha = isEnabled ? 255 : 128;

            WeakReference<ImageView> ref = overlayButtonRef;
            ImageView overlay = ref != null ? ref.get() : null;
            if (overlay != null) {
                applyLoadingOrActiveState(overlay, isEnabled, isLoading);
            }
            LegacyPlayerControlButton leg = legacy;
            if (leg != null) {
                leg.setImageAlpha(alpha);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "refreshActivatedState failure", ex);
        }
    }
}
