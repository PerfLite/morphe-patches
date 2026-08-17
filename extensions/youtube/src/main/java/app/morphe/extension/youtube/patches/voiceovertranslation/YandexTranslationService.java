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

public class YandexTranslationService {

    private static final String YANDEX_API_URL = "https://api.browser.yandex.ru/video-translation/translate";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 YaBrowser/24.1.5.825 Yowser/2.5 Safari/537.36";
    private static final String HMAC_KEY = "xtGCyGdTY2Jy6OMEKdTuXev3Twhkamgm";

    public static VideoTranslationResponse translate(String videoUrl, String originalLanguage, String translationLanguage, double originalDuration) {
        try {
            VideoTranslationRequest request = VideoTranslationRequest.newBuilder()
                    .setOriginalUrl(videoUrl)
                    .setOriginalLanguage(originalLanguage)
                    .setTranslationLanguage(translationLanguage)
                    .setOriginalDuration(originalDuration > 0 ? originalDuration : 900)
                    .setIsFirstRequest(true)
                    .build();

            byte[] requestBody = request.toByteArray();

            URL url = new URL(YANDEX_API_URL);
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
            
            // Add Yandex specific headers
            conn.setRequestProperty("sec-vtrans-token", UUID.randomUUID().toString());
            conn.setRequestProperty("vtrans-signature", generateSignature(requestBody));

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

            // Read response
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[1024];
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                return VideoTranslationResponse.parseFrom(buffer.toByteArray());
            }

        } catch (Exception e) {
            Logger.printException(() -> "YandexTranslationService error", e);
            return null;
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
