package app.morphe.extension.youtube.patches.voiceovertranslation;

import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.AudioBufferObject;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationAudioRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationResponse;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionResponse;

public class YandexTranslationService {

    private static final String YANDEX_BASE_URL = "https://api.browser.yandex.ru";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 YaBrowser/26.6.0.0 Safari/537.36";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.6.4.760";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    // Network requests happen before each API call, so one extra try per call is enough
    // to ride out transient failures without stalling the polling loop.
    private static final int MAX_NETWORK_RETRIES = 2;

    private static String cachedSecretKey = null;
    private static String cachedUuid = null;
    private static long sessionExpiryTimeMs = 0;

    private static synchronized String[] getOrRenewSession() {
        long now = System.currentTimeMillis();
        if (cachedSecretKey != null && cachedUuid != null && now < sessionExpiryTimeMs) {
            return new String[]{cachedUuid, cachedSecretKey};
        }

        for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
            if (attempt > 0) sleep(1000L * attempt);
            try {
                String rawUuid = UUID.randomUUID().toString().replace("-", "").toLowerCase();
                YandexSessionRequest sessionReq = YandexSessionRequest.newBuilder()
                        .setUuid(rawUuid)
                        .setModule("video-translation")
                        .build();

                byte[] body = sessionReq.toByteArray();
                String signature = generateSignature(body);

                URL url = new URL(YANDEX_BASE_URL + "/session/create");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Content-Type", "application/x-protobuf");
                conn.setRequestProperty("Accept", "application/x-protobuf");
                conn.setRequestProperty("Vtrans-Signature", signature);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printException(() -> "Yandex session creation failed with HTTP " + code);
                    continue;
                }

                byte[] respBytes = readAllBytes(conn.getInputStream());
                YandexSessionResponse sessionResp = YandexSessionResponse.parseFrom(respBytes);

                cachedUuid = rawUuid;
                cachedSecretKey = sessionResp.getSecretKey();
                int expires = sessionResp.getExpires();
                sessionExpiryTimeMs = now + ((long) (expires > 0 ? expires : 3600) * 1000) - 60000;

                return new String[]{cachedUuid, cachedSecretKey};
            } catch (Exception e) {
                Logger.printException(() -> "Yandex getOrRenewSession error", e);
            }
        }
        return null;
    }

    /** Invalidates the cached session so the next translate() call creates a fresh one. */
    public static synchronized void resetSession() {
        cachedSecretKey = null;
        cachedUuid = null;
        sessionExpiryTimeMs = 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static VideoTranslationResponse translate(String videoUrl, String originalLanguage, String translationLanguage, double originalDuration, boolean useLivelyVoice) {
        try {
            String[] session = getOrRenewSession();
            if (session == null) {
                Logger.printException(() -> "Could not obtain Yandex translation session");
                return null;
            }

            String uuid = session[0];
            String secretKey = session[1];

            // Normalize language codes (e.g. ru-RU -> ru)
            String targetLang = translationLanguage != null && translationLanguage.contains("-")
                    ? translationLanguage.split("-")[0].toLowerCase()
                    : (translationLanguage != null ? translationLanguage.toLowerCase() : "ru");

            String origLang = originalLanguage != null && originalLanguage.contains("-")
                    ? originalLanguage.split("-")[0].toLowerCase()
                    : (originalLanguage != null ? originalLanguage.toLowerCase() : "en");

            // Lively Voice is only allowed by Yandex when:
            // 1. The target (response) language is "ru"
            // 2. The source language is not "auto" (if it is "auto" and target is "ru", VOT maps it to "en")
            if (useLivelyVoice && "auto".equals(origLang) && "ru".equals(targetLang)) {
                origLang = "en";
            }
            boolean enableLively = useLivelyVoice && "ru".equals(targetLang) && !"auto".equals(origLang);

            VideoTranslationRequest request = VideoTranslationRequest.newBuilder()
                    .setOriginalUrl(videoUrl)
                    .setOriginalLanguage(origLang)
                    .setTranslationLanguage(targetLang)
                    .setOriginalDuration(originalDuration > 0 ? originalDuration : 310.0)
                    .setIsFirstRequest(true)
                    .setUnknown0(true)
                    .setUnknown2(true)
                    .setUnknown3(2)
                    .setUseLivelyVoice(enableLively)
                    .build();

            byte[] requestBody = request.toByteArray();
            String bodySignature = generateSignature(requestBody);

            String tokenPath = uuid + ":/video-translation/translate:" + COMPONENT_VERSION;
            String tokenSignature = generateSignature(tokenPath.getBytes(StandardCharsets.UTF_8));
            String fullToken = tokenSignature + ":" + tokenPath;

            for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
                if (attempt > 0) sleep(1000L * attempt);
                try {
                    URL url = new URL(YANDEX_BASE_URL + "/video-translation/translate");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Accept-Language", "en");
                    conn.setRequestProperty("Accept", "application/x-protobuf");
                    conn.setRequestProperty("Content-Type", "application/x-protobuf");
                    conn.setRequestProperty("Pragma", "no-cache");
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    conn.setRequestProperty("Sec-Fetch-Mode", "no-cors");

                    // Add current Yandex authentication headers
                    conn.setRequestProperty("Vtrans-Signature", bodySignature);
                    conn.setRequestProperty("Sec-Vtrans-Token", fullToken);
                    conn.setRequestProperty("Sec-Vtrans-Sk", secretKey);

                    // Write body
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(requestBody);
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        Logger.printException(() -> "Yandex API returned HTTP " + responseCode);
                        continue;
                    }

                    byte[] respBytes = readAllBytes(conn.getInputStream());
                    return VideoTranslationResponse.parseFrom(respBytes);
                } catch (Exception e) {
                    final int attemptNo = attempt;
                    Logger.printException(() -> "Yandex translate request failed (attempt " + attemptNo + ")", e);
                }
            }
            return null;
        } catch (Exception e) {
            Logger.printException(() -> "YandexTranslationService error", e);
            return null;
        }
    }

    /**
     * AUDIO_REQUESTED fallback for YouTube URLs, mirroring {@code @vot.js/core}
     * YandexProvider.translateVideo(): tell the backend the player-side audio download
     * failed and upload an empty placeholder, then let the caller re-request translate.
     */
    public static boolean reportAudioUnavailable(String videoUrl, String translationId) {
        try {
            if (videoUrl == null || translationId == null || translationId.isEmpty()) {
                return false;
            }

            String[] session = getOrRenewSession();
            if (session == null) return false;
            String uuid = session[0];
            String secretKey = session[1];

            // PUT /video-translation/fail-audio-js with a JSON body. This mirrors the
            // reference implementation, which sends this as plain JSON without the
            // protobuf signature headers.
            {
                byte[] body = ("{\"video_url\":\"" + videoUrl + "\"}").getBytes(StandardCharsets.UTF_8);

                URL url = new URL(YANDEX_BASE_URL + "/video-translation/fail-audio-js");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept-Language", "en");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Sec-Vtrans-Sk", secretKey);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printException(() -> "Yandex fail-audio-js returned HTTP " + code);
                    return false;
                }
            }

            // PUT /video-translation/audio with an empty audio buffer object.
            {
                VideoTranslationAudioRequest audioReq = VideoTranslationAudioRequest.newBuilder()
                        .setTranslationId(translationId)
                        .setOriginalUrl(videoUrl)
                        .setAudioInfo(AudioBufferObject.newBuilder()
                                .setFileId("web_api_get_all_generating_urls_data_from_iframe")
                                .setAudioFile(ByteString.EMPTY)
                                .build())
                        .build();
                byte[] body = audioReq.toByteArray();
                String signature = generateSignature(body);
                String tokenPath = uuid + ":/video-translation/audio:" + COMPONENT_VERSION;
                String tokenSignature = generateSignature(tokenPath.getBytes(StandardCharsets.UTF_8));

                URL url = new URL(YANDEX_BASE_URL + "/video-translation/audio");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Content-Type", "application/x-protobuf");
                conn.setRequestProperty("Accept", "application/x-protobuf");
                conn.setRequestProperty("Vtrans-Signature", signature);
                conn.setRequestProperty("Sec-Vtrans-Token", tokenSignature + ":" + tokenPath);
                conn.setRequestProperty("Sec-Vtrans-Sk", secretKey);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printException(() -> "Yandex audio upload returned HTTP " + code);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "Yandex reportAudioUnavailable error", e);
            return false;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            int nRead;
            byte[] data = new byte[2048];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        }
    }

    private static String generateSignature(byte[] body) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(body);
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
