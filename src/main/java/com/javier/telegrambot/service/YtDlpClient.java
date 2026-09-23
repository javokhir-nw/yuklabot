package com.javier.telegrambot.service;

import com.javier.telegrambot.MediaItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class YtDlpClient {

    private final ObjectMapper objectMapper;
    private static final String YT_DLP_COMMAND = "yt-dlp";
    private static final int PROCESS_TIMEOUT_SECONDS = 60;

    public YtDlpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MediaItem> resolveMedia(String url) {
        try {
            log.info("Processing URL via yt-dlp: {}", url);

            ProcessBuilder pb = new ProcessBuilder(
                    YT_DLP_COMMAND,
                    "-J",
                    "--no-warnings",
                    "--no-playlist",
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

            if (exitCode != 0) {
                log.error("yt-dlp exit code {} for URL: {}. stderr: {}", exitCode, url, errorOutput);
                throw new RuntimeException("yt-dlp failed: " + errorOutput);
            }

            if (jsonOutput == null || jsonOutput.isBlank()) {
                log.error("yt-dlp returned empty output for URL: {}", url);
                return Collections.emptyList();
            }

            JsonNode rootNode = objectMapper.readTree(jsonOutput);
            List<MediaItem> items = parseResponse(rootNode);
            log.info("Resolved {} media item(s) for URL: {}", items.size(), url);
            return items;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to resolve media for URL: {}", url, e);
            throw new RuntimeException("Media processing failed", e);
        }
    }

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
                    addMediaItemFromJson(entry, items);
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

        // 2-fallback: formats massividan eng yaxshi mp4 formatni izlash
        if (isBlank(downloadUrl) && node.has("formats")) {
            JsonNode formats = node.get("formats");
            if (formats.isArray()) {
                downloadUrl = findBestVideoUrl(formats);
            }
        }

        // 3-fallback: thumbnails dan rasm URL izlash (rasm postlar uchun)
        if (isBlank(downloadUrl) && node.has("thumbnail")) {
            downloadUrl = getTextSafe(node, "thumbnail");
            if (!isBlank(downloadUrl)) {
                items.add(new MediaItem(downloadUrl, "image"));
                return;
            }
        }

        if (isBlank(downloadUrl)) {
            log.warn("No download URL found for id: {}. Available keys: {}",
                    getTextSafe(node, "id"), getJsonKeys(node));
            return;
        }

        String mediaType = detectMediaType(node, downloadUrl);
        items.add(new MediaItem(downloadUrl, mediaType));
    }

    /**
     * formats massividan eng yaxshi video URL ni topish.
     * mp4 formatdagi eng katta o'lchamli yoki eng yuqori sifatli variantni tanlaydi.
     */
    private String findBestVideoUrl(JsonNode formats) {
        String bestUrl = null;
        int bestHeight = -1;
        long bestFilesize = -1;

        for (JsonNode fmt : formats) {
            String fmtUrl = getTextSafe(fmt, "url");
            if (isBlank(fmtUrl)) continue;

            String ext = getTextSafe(fmt, "ext");
            String vcodec = getTextSafe(fmt, "vcodec");
            String acodec = getTextSafe(fmt, "acodec");

            // Faqat video codec bor formatlarni afzal ko'rish
            boolean hasVideo = vcodec != null && !"none".equals(vcodec);
            boolean hasAudio = acodec != null && !"none".equals(acodec);

            // Video + audio birgalikda eng yaxshi
            if (hasVideo) {
                int height = fmt.has("height") && !fmt.get("height").isNull()
                        ? fmt.get("height").asInt(0) : 0;
                long filesize = fmt.has("filesize") && !fmt.get("filesize").isNull()
                        ? fmt.get("filesize").asLong(0) : 0;

                // Prioritet: video+audio > faqat video, keyin sifat bo'yicha
                boolean isBetter = false;
                if (bestUrl == null) {
                    isBetter = true;
                } else if (height > bestHeight) {
                    isBetter = true;
                } else if (height == bestHeight && filesize > bestFilesize) {
                    isBetter = true;
                }

                if (isBetter) {
                    bestUrl = fmtUrl;
                    bestHeight = height;
                    bestFilesize = filesize;
                }
            }
        }

        // Agar video topilmasa, har qanday formatni olish
        if (bestUrl == null) {
            for (JsonNode fmt : formats) {
                String fmtUrl = getTextSafe(fmt, "url");
                if (!isBlank(fmtUrl)) {
                    return fmtUrl;
                }
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

    private String getJsonKeys(JsonNode node) {
        if (node == null || !node.isObject()) return "[]";
        return new ArrayList<>(node.propertyNames()).toString();
    }
}
