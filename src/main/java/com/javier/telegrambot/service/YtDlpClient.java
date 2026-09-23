package com.javier.telegrambot.service;

import com.javier.telegrambot.MediaItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class YtDlpClient {

    private final ObjectMapper objectMapper;
    private static final String YT_DLP_COMMAND = "yt-dlp";
    private static final int PROCESS_TIMEOUT_SECONDS = 60;
    private final HttpClient httpClient;

    public YtDlpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * URL dan media URL larni ajratib oladi. Serverga fayl yuklamaydi —
     * faqat CDN URL qaytaradi, Telegram serverlari o'zi yuklaydi.
     */
    public List<MediaItem> resolveMedia(String url) {
        // 1-qadam: yt-dlp orqali urinish (video/reels uchun)
        try {
            List<MediaItem> items = resolveViaYtDlp(url);
            if (!items.isEmpty()) return items;
        } catch (Exception e) {
            log.warn("yt-dlp failed for URL: {}. Error: {}", url, e.getMessage());
        }

    // 2-qadam: gallery-dl fallback (ayniqsa rasm karusellari uchun)
        if (url.contains("instagram.com")) {
            try {
                List<MediaItem> items = resolveViaGalleryDl(url);
                if (!items.isEmpty()) return items;
            } catch (Exception e) {
                log.warn("gallery-dl fallback failed for URL: {}: {}", url, e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    // ================================================================
    // YT-DLP — VIDEO URL OLISH
    // ================================================================

    private List<MediaItem> resolveViaYtDlp(String url) throws Exception {
        log.info("Processing URL via yt-dlp: {}", url);

        ProcessBuilder pb = new ProcessBuilder(
                YT_DLP_COMMAND,
                "-J",
                "--no-warnings",
                "--ignore-errors",
                "--format", "b",       // best single-stream (video+audio birgalikda)
                url
        );

        Process process = pb.start();
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("yt-dlp timed out");
        }

        String jsonOutput = stdoutFuture.get(5, TimeUnit.SECONDS);
        String stderrOutput = stderrFuture.get(5, TimeUnit.SECONDS);
        int exitCode = process.exitValue();

        if (jsonOutput != null && !jsonOutput.isBlank()) {
            try {
                JsonNode rootNode = objectMapper.readTree(jsonOutput);
                List<MediaItem> items = parseResponse(rootNode);
                log.info("yt-dlp resolved {} media item(s) for URL: {}", items.size(), url);
                if (!items.isEmpty()) return items;
            } catch (Exception e) {
                log.warn("Failed to parse yt-dlp JSON: {}", e.getMessage());
            }
        }

        if (exitCode != 0) {
            log.warn("yt-dlp exit code {} for URL: {}. stderr: {}", exitCode, url,
                    stderrOutput.length() > 300 ? stderrOutput.substring(0, 300) : stderrOutput);
            throw new RuntimeException("yt-dlp failed with exit code " + exitCode);
        }

        return Collections.emptyList();
    }

    // ================================================================
    // GALLERY-DL — RASM/KARUSEL FALLBACK
    // ================================================================

    private List<MediaItem> resolveViaGalleryDl(String url) throws Exception {
        log.info("Trying gallery-dl fallback for URL: {}", url);

        ProcessBuilder pb = new ProcessBuilder(
                "gallery-dl",
                "-j",
                "--ignore-config",
                url
        );

        Process process = pb.start();
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("gallery-dl timed out");
        }

        String jsonOutput = stdoutFuture.get(5, TimeUnit.SECONDS);
        String stderrOutput = stderrFuture.get(5, TimeUnit.SECONDS);

        if (jsonOutput == null || jsonOutput.isBlank()) {
            log.warn("gallery-dl returned empty stdout. stderr: {}", stderrOutput);
            return Collections.emptyList();
        }

        List<MediaItem> items = new ArrayList<>();
        
        try {
            // gallery-dl JSON format: odatda root array bo'ladi [ [Type, "Url", {metadata}], ... ]
            JsonNode rootNode = objectMapper.readTree(jsonOutput);
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    if (node.isArray()) {
                        // Odatda indeks 1 da to'g'ridan-to'g'ri URL yotadi 
                        for (JsonNode innerNode : node) {
                            if (innerNode.isTextual()) {
                                String imgUrl = innerNode.asText();
                                if (imgUrl != null && imgUrl.startsWith("http")) {
                                    // Agar rasm URL si bo'lsa
                                    if (imgUrl.contains(".jpg") || imgUrl.contains(".webp") || imgUrl.contains(".png")) {
                                        items.add(new MediaItem(imgUrl.replace("\\u0026", "&"), "image"));
                                        break; // Shu element uchun bitta URL yetarli
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse gallery-dl JSON", e);
        }

        log.info("gallery-dl fallback resolved {} media item(s) for URL: {}", items.size(), url);
        return items;
    }

    // ================================================================
    // JSON PARSE
    // ================================================================

    private List<MediaItem> parseResponse(JsonNode rootNode) {
        List<MediaItem> items = new ArrayList<>();
        if (rootNode == null) return items;

        String type = rootNode.has("_type") ? rootNode.get("_type").asText() : "";

        if ("playlist".equalsIgnoreCase(type)) {
            JsonNode entries = rootNode.get("entries");
            if (entries != null && entries.isArray()) {
                for (JsonNode entry : entries) {
                    if (entry != null && !entry.isNull()) {
                        addMediaItemFromJson(entry, items);
                    }
                }
            }
        } else {
            addMediaItemFromJson(rootNode, items);
        }

        return items;
    }

    private void addMediaItemFromJson(JsonNode node, List<MediaItem> items) {
        if (node == null) return;

        String downloadUrl = getTextSafe(node, "url");

        // 1-fallback: requested_downloads
        if (isBlank(downloadUrl) && node.has("requested_downloads")) {
            JsonNode requested = node.get("requested_downloads");
            if (requested.isArray() && requested.size() > 0) {
                downloadUrl = getTextSafe(requested.get(0), "url");
            }
        }

        // 2-fallback: formats massividan video+audio birgalikda
        if (isBlank(downloadUrl) && node.has("formats")) {
            JsonNode formats = node.get("formats");
            if (formats.isArray()) {
                downloadUrl = findBestCombinedUrl(formats);
            }
        }

        // 3-fallback: thumbnail (rasm sifatida)
        if (isBlank(downloadUrl) && node.has("thumbnail")) {
            downloadUrl = getTextSafe(node, "thumbnail");
            if (!isBlank(downloadUrl)) {
                items.add(new MediaItem(downloadUrl, "image"));
                return;
            }
        }

        if (isBlank(downloadUrl)) {
            log.warn("No URL found for id: {}", getTextSafe(node, "id"));
            return;
        }

        String mediaType = detectMediaType(node, downloadUrl);
        items.add(new MediaItem(downloadUrl, mediaType));
    }

    /**
     * formats massividan video+audio birgalikda bo'lgan eng yaxshi formatni topadi.
     * Bu GIF muammosini hal qiladi: Telegram audiosiz videolarni GIF deb ko'rsatadi.
     */
    private String findBestCombinedUrl(JsonNode formats) {
        String bestCombinedUrl = null;   // video + audio
        int bestCombinedHeight = -1;
        String bestVideoOnlyUrl = null;  // faqat video (fallback)
        int bestVideoOnlyHeight = -1;

        for (JsonNode fmt : formats) {
            String fmtUrl = getTextSafe(fmt, "url");
            if (isBlank(fmtUrl)) continue;

            String vcodec = getTextSafe(fmt, "vcodec");
            String acodec = getTextSafe(fmt, "acodec");
            boolean hasVideo = vcodec != null && !"none".equals(vcodec);
            boolean hasAudio = acodec != null && !"none".equals(acodec);
            int height = fmt.has("height") && !fmt.get("height").isNull() ? fmt.get("height").asInt(0) : 0;

            if (hasVideo && hasAudio) {
                // Eng yaxshi: video + audio bitta streamda
                if (height > bestCombinedHeight) {
                    bestCombinedUrl = fmtUrl;
                    bestCombinedHeight = height;
                }
            } else if (hasVideo && bestCombinedUrl == null) {
                // Fallback: faqat video
                if (height > bestVideoOnlyHeight) {
                    bestVideoOnlyUrl = fmtUrl;
                    bestVideoOnlyHeight = height;
                }
            }
        }

        return bestCombinedUrl != null ? bestCombinedUrl : bestVideoOnlyUrl;
    }

    private String detectMediaType(JsonNode node, String url) {
        String ext = getTextSafe(node, "ext");
        if (ext != null) {
            return switch (ext.toLowerCase()) {
                case "mp4", "webm", "mkv", "mov" -> "video";
                case "jpg", "jpeg", "png", "webp" -> "image";
                default -> "video";
            };
        }
        if (url.contains(".jpg") || url.contains(".webp") || url.contains(".png")) return "image";
        return "video";
    }

    // ================================================================
    // YORDAMCHI METODLAR
    // ================================================================

    private CompletableFuture<String> readStreamAsync(java.io.InputStream is) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } catch (Exception e) {
                log.error("Error reading process stream", e);
            }
            return sb.toString();
        });
    }

    private String getTextSafe(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String val = node.get(field).asText();
            return val.isBlank() ? null : val;
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
