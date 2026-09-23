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

    public List<MediaItem> resolveMedia(String url) {
        // 1-qadam: yt-dlp orqali urinish (video uchun)
        try {
            List<MediaItem> items = resolveViaYtDlp(url);
            if (!items.isEmpty()) {
                return items;
            }
        } catch (Exception e) {
            log.warn("yt-dlp failed for URL: {}. Trying embed fallback. Error: {}", url, e.getMessage());
        }

        // 2-qadam: Instagram embed API orqali urinish (rasm uchun)
        if (url.contains("instagram.com")) {
            try {
                List<MediaItem> items = resolveViaInstagramEmbed(url);
                if (!items.isEmpty()) {
                    return items;
                }
            } catch (Exception e) {
                log.warn("Instagram embed fallback also failed for URL: {}: {}", url, e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    // ============== YT-DLP METODI ==============

    private List<MediaItem> resolveViaYtDlp(String url) throws Exception {
        log.info("Processing URL via yt-dlp: {}", url);

        ProcessBuilder pb = new ProcessBuilder(
                YT_DLP_COMMAND,
                "-J",
                "--no-warnings",
                "--ignore-errors",
                url
        );

        Process process = pb.start();

        // stdout va stderr ni parallel o'qish — deadlock bo'lmasligi uchun
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.error("yt-dlp timed out after {}s for URL: {}", PROCESS_TIMEOUT_SECONDS, url);
            throw new RuntimeException("yt-dlp execution timed out");
        }

        int exitCode = process.exitValue();
        String jsonOutput = stdoutFuture.get(5, TimeUnit.SECONDS);
        String errorOutput = stderrFuture.get(5, TimeUnit.SECONDS);

        // --ignore-errors bilan exit code 0 yoki 1 bo'lishi mumkin,
        // lekin JSON chiqish bo'lsa uni parse qilishga harakat qilamiz
        if (jsonOutput != null && !jsonOutput.isBlank()) {
            try {
                JsonNode rootNode = objectMapper.readTree(jsonOutput);
                List<MediaItem> items = parseResponse(rootNode);
                log.info("yt-dlp resolved {} media item(s) for URL: {}", items.size(), url);
                if (!items.isEmpty()) {
                    return items;
                }
            } catch (Exception e) {
                log.warn("Failed to parse yt-dlp JSON output: {}", e.getMessage());
            }
        }

        if (exitCode != 0) {
            log.error("yt-dlp exit code {} for URL: {}. stderr: {}", exitCode, url,
                    errorOutput.length() > 300 ? errorOutput.substring(0, 300) : errorOutput);
            throw new RuntimeException("yt-dlp failed with exit code " + exitCode);
        }

        return Collections.emptyList();
    }

    // ============== INSTAGRAM EMBED FALLBACK ==============

    private List<MediaItem> resolveViaInstagramEmbed(String url) throws Exception {
        log.info("Trying Instagram embed fallback for URL: {}", url);

        // Query parametrlarni olib tashlash
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        if (!cleanUrl.endsWith("/")) cleanUrl += "/";

        String embedUrl = cleanUrl + "embed/";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embedUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        List<MediaItem> items = new ArrayList<>();

        // Video URL larni izlash
        Pattern videoPattern = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"");
        Matcher videoMatcher = videoPattern.matcher(html);
        while (videoMatcher.find()) {
            String videoUrl = videoMatcher.group(1).replace("\\u0026", "&").replace("\\/", "/");
            items.add(new MediaItem(videoUrl, "video"));
        }

        // Rasm URL larni izlash (display_url yoki src)
        if (items.isEmpty()) {
            // Birinchi EmbeddedMediaImage src ni izlash
            Pattern imgPattern = Pattern.compile("class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\"");
            Matcher imgMatcher = imgPattern.matcher(html);
            while (imgMatcher.find()) {
                String imgUrl = imgMatcher.group(1).replace("&amp;", "&");
                items.add(new MediaItem(imgUrl, "image"));
            }

            // Fallback: display_url dan izlash
            if (items.isEmpty()) {
                Pattern displayPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"");
                Matcher displayMatcher = displayPattern.matcher(html);
                while (displayMatcher.find()) {
                    String imgUrl = displayMatcher.group(1).replace("\\u0026", "&").replace("\\/", "/");
                    items.add(new MediaItem(imgUrl, "image"));
                }
            }
        }

        log.info("Instagram embed resolved {} media item(s) for URL: {}", items.size(), url);
        return items;
    }

    // ============== JSON PARSE METODLARI ==============

    private CompletableFuture<String> readStreamAsync(java.io.InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
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

        // 1-fallback: requested_downloads dan izlash
        if (isBlank(downloadUrl) && node.has("requested_downloads")) {
            JsonNode requested = node.get("requested_downloads");
            if (requested.isArray() && requested.size() > 0) {
                downloadUrl = getTextSafe(requested.get(0), "url");
            }
        }

        // 2-fallback: formats massividan eng yaxshi video formatni izlash
        if (isBlank(downloadUrl) && node.has("formats")) {
            JsonNode formats = node.get("formats");
            if (formats.isArray()) {
                downloadUrl = findBestVideoUrl(formats);
            }
        }

        // 3-fallback: thumbnail (rasm postlar uchun)
        if (isBlank(downloadUrl) && node.has("thumbnail")) {
            downloadUrl = getTextSafe(node, "thumbnail");
            if (!isBlank(downloadUrl)) {
                items.add(new MediaItem(downloadUrl, "image"));
                return;
            }
        }

        if (isBlank(downloadUrl)) {
            log.warn("No download URL found for id: {}", getTextSafe(node, "id"));
            return;
        }

        String mediaType = detectMediaType(node, downloadUrl);
        items.add(new MediaItem(downloadUrl, mediaType));
    }

    private String findBestVideoUrl(JsonNode formats) {
        String bestUrl = null;
        int bestHeight = -1;

        for (JsonNode fmt : formats) {
            String fmtUrl = getTextSafe(fmt, "url");
            if (isBlank(fmtUrl)) continue;

            String vcodec = getTextSafe(fmt, "vcodec");
            boolean hasVideo = vcodec != null && !"none".equals(vcodec);

            if (hasVideo) {
                int height = fmt.has("height") && !fmt.get("height").isNull()
                        ? fmt.get("height").asInt(0) : 0;

                if (bestUrl == null || height > bestHeight) {
                    bestUrl = fmtUrl;
                    bestHeight = height;
                }
            }
        }

        // Agar video topilmasa, har qanday formatni olish
        if (bestUrl == null) {
            for (JsonNode fmt : formats) {
                String fmtUrl = getTextSafe(fmt, "url");
                if (!isBlank(fmtUrl)) return fmtUrl;
            }
        }

        return bestUrl;
    }

    private String detectMediaType(JsonNode node, String downloadUrl) {
        String ext = getTextSafe(node, "ext");
        if (ext != null) {
            return switch (ext.toLowerCase()) {
                case "mp4", "webm", "mkv", "mov" -> "video";
                case "jpg", "jpeg", "png", "webp" -> "image";
                default -> "video";
            };
        }
        if (downloadUrl.contains(".jpg") || downloadUrl.contains(".webp") || downloadUrl.contains(".png")) {
            return "image";
        }
        return "video";
    }

    private String getTextSafe(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String value = node.get(field).asText();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
