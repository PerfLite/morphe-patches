package app.morphe.extension.youtube.patches.voiceovertranslation;

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
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.VideoTranslationResponse;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionRequest;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.Vtrans.YandexSessionResponse;

public class YandexTranslationService {

    private static final String YANDEX_BASE_URL = "https://api.browser.yandex.ru";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 YaBrowser/26.6.0.0 Safari/537.36";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.6.4.760";

    private static String cachedSecretKey = null;
    private static String cachedUuid = null;
    private static long sessionExpiryTimeMs = 0;

    private static synchronized String[] getOrRenewSession() {
        long now = System.currentTimeMillis();
        if (cachedSecretKey != null && cachedUuid != null && now < sessionExpiryTimeMs) {
            return new String[]{cachedUuid, cachedSecretKey};
        }

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
                return null;
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
            return null;
        }
    }

    /** Invalidates the cached session so the next translate() call creates a fresh one. */
    public static synchronized void resetSession() {
        cachedSecretKey = null;
        cachedUuid = null;
        sessionExpiryTimeMs = 0;
    }

    public static VideoTranslationResponse translate(String videoUrl, String originalLanguage, String translationLanguage, double originalDuration) {
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

            VideoTranslationRequest request = VideoTranslationRequest.newBuilder()
                    .setOriginalUrl(videoUrl)
                    .setOriginalLanguage(origLang)
                    .setTranslationLanguage(targetLang)
                    .setOriginalDuration(originalDuration > 0 ? originalDuration : 310.0)
                    .setIsFirstRequest(true)
                    .setUseLivelyVoice(app.morphe.extension.youtube.settings.Settings.VOT_YANDEX_LIVELY_VOICE.get())
                    .build();

            byte[] requestBody = request.toByteArray();
            String bodySignature = generateSignature(requestBody);

            String tokenPath = uuid + ":/video-translation/translate:" + COMPONENT_VERSION;
            String tokenSignature = generateSignature(tokenPath.getBytes(StandardCharsets.UTF_8));
            String fullToken = tokenSignature + ":" + tokenPath;

            URL url = new URL(YANDEX_BASE_URL + "/video-translation/translate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                return null;
            }

            byte[] respBytes = readAllBytes(conn.getInputStream());
            return VideoTranslationResponse.parseFrom(respBytes);

        } catch (Exception e) {
            Logger.printException(() -> "YandexTranslationService error", e);
            return null;
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
