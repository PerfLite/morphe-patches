package app.morphe.extension.youtube.patches.voiceovertranslation;

import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;
import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.AudioBufferObject;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationAudioRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationResponse;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionResponse;

public class YandexTranslationService {

    private static final String YANDEX_BASE_URL = "https://api.browser.yandex.ru";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 YaBrowser/26.10.0.1796 Safari/537.36";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.10.0.1796";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_NETWORK_RETRIES = 2;

    private static String cachedSecretKey = null;
    private static String sessionUuid = null;
    private static long sessionExpiryTimeMs = 0;
    private static volatile long rateLimitUntilMs = 0;

    public static boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitUntilMs;
    }

    public static boolean isProxyEnabled() {
        return Settings.VOT_PROXY_ENABLED.get();
    }

    private static String getBaseUrl() {
        if (isProxyEnabled()) {
            String host = Settings.VOT_PROXY_HOST.get().trim();
            if (!host.isEmpty()) {
                if (!host.startsWith("http://") && !host.startsWith("https://")) {
                    host = "https://" + host;
                }
                while (host.endsWith("/")) {
                    host = host.substring(0, host.length() - 1);
                }
                return host;
            }
        }
        return YANDEX_BASE_URL;
    }

    private static String generateUuid() {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        Random rnd = new Random();
        char[] buf = new char[32];
        for (int i = 0; i < 32; i++) {
            buf[i] = hex[rnd.nextInt(16)];
        }
        return new String(buf);
    }

    private static void applyClientHeaders(HttpURLConnection conn) {
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"YaBrowser\";v=\"26.10\", \"Yowser\";v=\"2.5\"");
        conn.setRequestProperty("sec-ch-ua-mobile", "?0");
        conn.setRequestProperty("sec-ch-ua-platform", "\"Windows\"");
        conn.setRequestProperty("sec-ch-ua-full-version-list", "\"Not;A=Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"150.0.7871.1796\", \"YaBrowser\";v=\"26.10.0.1796\", \"Yowser\";v=\"2.5\"");
    }

    private static void setupConnectionHeaders(HttpURLConnection conn, String contentType, String signature, String token, String sk) {
        if (isProxyEnabled()) {
            try {
                JSONObject headers = new JSONObject();
                headers.put("User-Agent", USER_AGENT);
                headers.put("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"YaBrowser\";v=\"26.10\", \"Yowser\";v=\"2.5\"");
                headers.put("sec-ch-ua-mobile", "?0");
                headers.put("sec-ch-ua-platform", "\"Windows\"");
                headers.put("sec-ch-ua-full-version-list", "\"Not;A=Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"150.0.7871.1796\", \"YaBrowser\";v=\"26.10.0.1796\", \"Yowser\";v=\"2.5\"");
                headers.put("Accept-Language", "en");
                headers.put("Content-Type", contentType);
                headers.put("Accept", contentType);
                headers.put("Pragma", "no-cache");
                headers.put("Cache-Control", "no-cache");
                if (signature != null && !signature.isEmpty()) {
                    headers.put("Vtrans-Signature", signature);
                }
                if (token != null && !token.isEmpty()) {
                    headers.put("Sec-Vtrans-Token", token);
                }
                if (sk != null && !sk.isEmpty()) {
                    headers.put("Sec-Vtrans-Sk", sk);
                }
                String b64 = Base64.encodeToString(headers.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                conn.setRequestProperty("User-Agent", "vot.js/3.1.5");
                conn.setRequestProperty("X-VOT-Headers", b64);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setRequestProperty("Accept", contentType);
            } catch (Exception e) {
                Logger.printException(() -> "Failed to encode VOT worker headers", e);
            }
        } else {
            applyClientHeaders(conn);
            conn.setRequestProperty("Accept-Language", "en");
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Accept", contentType);
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (signature != null && !signature.isEmpty()) {
                conn.setRequestProperty("Vtrans-Signature", signature);
            }
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Sec-Vtrans-Token", token);
            }
            if (sk != null && !sk.isEmpty()) {
                conn.setRequestProperty("Sec-Vtrans-Sk", sk);
            }
        }
    }

    private static synchronized String[] getOrRenewSession() {
        long now = System.currentTimeMillis();
        if (cachedSecretKey != null && sessionUuid != null && now < sessionExpiryTimeMs) {
            return new String[]{sessionUuid, cachedSecretKey};
        }
        if (isRateLimited()) {
            return null;
        }

        for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
            if (attempt > 0) sleep(1000L * attempt);
            try {
                final String reqUuid = generateUuid();
                YandexSessionRequest sessionReq = YandexSessionRequest.newBuilder()
                        .setUuid(reqUuid)
                        .setModule("video-translation")
                        .build();

                byte[] body = sessionReq.toByteArray();
                String signature = generateSignature(body);

                URL url = new URL(getBaseUrl() + "/session/create");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                setupConnectionHeaders(conn, "application/x-protobuf", signature, null, null);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 429) {
                    rateLimitUntilMs = System.currentTimeMillis() + 60_000L;
                    resetSession();
                    Logger.printException(() -> "Yandex session creation rate limited (HTTP 429)");
                    if (!isProxyEnabled()) {
                        Utils.showToastShort("⚠️ Лимит запросов Яндекс (429). Включите VOT Прокси в настройках.");
                    } else {
                        Utils.showToastShort("⚠️ Превышен лимит запросов к Яндекс (HTTP 429), подождите 1 минуту");
                    }
                    return null;
                }
                if (code != 200) {
                    Logger.printException(() -> "Yandex session creation failed with HTTP " + code);
                    if (code == 400 || code == 401 || code == 403) {
                        resetSession();
                        return null;
                    }
                    continue;
                }

                byte[] respBytes = readAllBytes(conn.getInputStream());
                YandexSessionResponse sessionResp = YandexSessionResponse.parseFrom(respBytes);

                sessionUuid = reqUuid;
                cachedSecretKey = sessionResp.getSecretKey();
                int expires = sessionResp.getExpires();
                sessionExpiryTimeMs = now + ((long) (expires > 0 ? expires : 3600) * 1000) - 60000;

                return new String[]{sessionUuid, cachedSecretKey};
            } catch (Exception e) {
                Logger.printException(() -> "Yandex getOrRenewSession error", e);
            }
        }
        return null;
    }

    /**
     * Resets both the cached secret key and session uuid so a fresh session
     * with a new UUID is generated on next request.
     */
    public static synchronized void resetSession() {
        cachedSecretKey = null;
        sessionUuid = null;
        sessionExpiryTimeMs = 0;
        rateLimitUntilMs = 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static VideoTranslationResponse translate(String videoUrl, String originalLanguage, String translationLanguage, double originalDuration, boolean useLivelyVoice) {
        return translate(videoUrl, originalLanguage, translationLanguage, originalDuration, useLivelyVoice, null);
    }

    public static VideoTranslationResponse translate(String videoUrl, String originalLanguage, String translationLanguage, double originalDuration, boolean useLivelyVoice, String videoTitle) {
        if (isRateLimited()) {
            Logger.printDebug(() -> "Yandex translation skipped due to active rate limit (cooldown)");
            return null;
        }
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

            VideoTranslationRequest.Builder reqBuilder = VideoTranslationRequest.newBuilder()
                    .setOriginalUrl(videoUrl)
                    .setOriginalLanguage(origLang)
                    .setTranslationLanguage(targetLang)
                    .setOriginalDuration(originalDuration > 0 ? originalDuration : 310.0)
                    .setIsFirstRequest(true)
                    .setUnknown0(true)
                    .setUnknown2(true)
                    .setConfigVersion(2)
                    .setUseLivelyVoice(enableLively);

            if (videoTitle != null && !videoTitle.trim().isEmpty()) {
                reqBuilder.setVideoTitle(videoTitle.trim());
            }

            VideoTranslationRequest request = reqBuilder.build();

            byte[] requestBody = request.toByteArray();
            String bodySignature = generateSignature(requestBody);

            String tokenPath = uuid + ":/video-translation/translate:" + COMPONENT_VERSION;
            String tokenSignature = generateSignature(tokenPath.getBytes(StandardCharsets.UTF_8));
            String fullToken = tokenSignature + ":" + tokenPath;

            for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
                if (attempt > 0) sleep(1000L * attempt);
                try {
                    URL url = new URL(getBaseUrl() + "/video-translation/translate");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    setupConnectionHeaders(conn, "application/x-protobuf", bodySignature, fullToken, secretKey);

                    // Write body
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(requestBody);
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 429) {
                        rateLimitUntilMs = System.currentTimeMillis() + 60_000L;
                        resetSession();
                        Logger.printException(() -> "Yandex API rate limited (HTTP 429)");
                        if (!isProxyEnabled()) {
                            Utils.showToastShort("⚠️ Лимит запросов Яндекс (429). Включите VOT Прокси в настройках.");
                        } else {
                            Utils.showToastShort("⚠️ Превышен лимит запросов к Яндекс (HTTP 429), подождите 1 минуту");
                        }
                        return null;
                    }
                    if (responseCode != 200) {
                        Logger.printException(() -> "Yandex API returned HTTP " + responseCode);
                        if (responseCode == 400 || responseCode == 401 || responseCode == 403) {
                            resetSession();
                            return null;
                        }
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
        if (isRateLimited()) return false;
        try {
            if (videoUrl == null || translationId == null || translationId.isEmpty()) {
                return false;
            }

            String[] session = getOrRenewSession();
            if (session == null) return false;
            String uuid = session[0];
            String secretKey = session[1];

            // PUT /video-translation/fail-audio-js with a JSON body.
            {
                byte[] body = ("{\"video_url\":\"" + videoUrl + "\"}").getBytes(StandardCharsets.UTF_8);

                URL url = new URL(getBaseUrl() + "/video-translation/fail-audio-js");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                setupConnectionHeaders(conn, "application/json", null, null, secretKey);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 429) {
                    rateLimitUntilMs = System.currentTimeMillis() + 60_000L;
                    resetSession();
                    if (!isProxyEnabled()) {
                        Utils.showToastShort("⚠️ Лимит запросов Яндекс (429). Включите VOT Прокси в настройках.");
                    } else {
                        Utils.showToastShort("⚠️ Превышен лимит запросов к Яндекс (HTTP 429), подождите 1 минуту");
                    }
                    return false;
                }
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

                URL url = new URL(getBaseUrl() + "/video-translation/audio");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                setupConnectionHeaders(conn, "application/x-protobuf", signature, tokenSignature + ":" + tokenPath, secretKey);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 429) {
                    rateLimitUntilMs = System.currentTimeMillis() + 60_000L;
                    resetSession();
                    if (!isProxyEnabled()) {
                        Utils.showToastShort("⚠️ Лимит запросов Яндекс (429). Включите VOT Прокси в настройках.");
                    } else {
                        Utils.showToastShort("⚠️ Превышен лимит запросов к Яндекс (HTTP 429), подождите 1 минуту");
                    }
                    return false;
                }
                if (code != 200) {
                    Logger.printException(() -> "Yandex audio upload returned HTTP " + code);
                    if (code == 400 || code == 401 || code == 403) {
                        resetSession();
                    }
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
