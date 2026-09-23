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
            // stderr ni stdout ga birlashtirish orqali deadlock oldini olamiz
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // stdout ni alohida thread da o'qish — process bloklanmasligi uchun
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                } catch (Exception e) {
                    log.error("Error reading yt-dlp output", e);
                }
                return sb.toString();
            });

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("yt-dlp execution timed out after {} seconds for URL: {}", PROCESS_TIMEOUT_SECONDS, url);
                throw new RuntimeException("yt-dlp execution timed out");
            }

            String fullOutput = outputFuture.get(5, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.error("yt-dlp failed with exit code {} for URL: {}. Output: {}", exitCode, url,
                        fullOutput.length() > 500 ? fullOutput.substring(0, 500) : fullOutput);
                throw new RuntimeException("yt-dlp execution failed with exit code " + exitCode);
            }

            if (fullOutput.isBlank()) {
                log.error("yt-dlp returned empty output for URL: {}", url);
                return Collections.emptyList();
            }

            // JSON ni ajratib olish (stderr ham aralashgan bo'lishi mumkin)
            String jsonOutput = extractJson(fullOutput);
            if (jsonOutput == null || jsonOutput.isBlank()) {
                log.error("Could not extract valid JSON from yt-dlp output for URL: {}", url);
                return Collections.emptyList();
            }

            JsonNode rootNode = objectMapper.readTree(jsonOutput);
            List<MediaItem> items = parseResponse(rootNode);
            log.info("Resolved {} media item(s) for URL: {}", items.size(), url);
            return items;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to resolve media using yt-dlp for URL: {}", url, e);
            throw new RuntimeException("Media processing failed", e);
        }
    }

    /**
     * redirectErrorStream(true) bo'lganda, stderr va stdout aralashgan bo'lishi mumkin.
     * JSON qismini topib ajratib olish.
     */
    private String extractJson(String output) {
        int braceStart = output.indexOf('{');
        if (braceStart == -1) return null;

        int depth = 0;
        for (int i = braceStart; i < output.length(); i++) {
            char c = output.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0) {
                return output.substring(braceStart, i + 1);
            }
        }
        return null;
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

        // Fallback: requested_downloads dan izlash
        if (downloadUrl == null || downloadUrl.isBlank()) {
            if (node.has("requested_downloads") && node.get("requested_downloads").isArray()) {
                JsonNode requested = node.get("requested_downloads");
                if (requested.size() > 0) {
                    downloadUrl = getTextSafe(requested.get(0), "url");
                }
            }
        }

        if (downloadUrl == null || downloadUrl.isBlank()) {
            log.warn("No download URL found in JSON node with id: {}", getTextSafe(node, "id"));
            return;
        }

        // Turi aniqlash: video yoki rasm
        String ext = getTextSafe(node, "ext");
        String mediaType;

        if (ext != null && (ext.equalsIgnoreCase("mp4") || ext.equalsIgnoreCase("webm")
                || ext.equalsIgnoreCase("mkv") || ext.equalsIgnoreCase("mov"))) {
            mediaType = "video";
        } else if (ext != null && (ext.equalsIgnoreCase("jpg") || ext.equalsIgnoreCase("jpeg")
                || ext.equalsIgnoreCase("png") || ext.equalsIgnoreCase("webp"))) {
            mediaType = "image";
        } else if (downloadUrl.contains(".mp4") || downloadUrl.contains(".webm")) {
            mediaType = "video";
        } else if (downloadUrl.contains(".jpg") || downloadUrl.contains(".webp") || downloadUrl.contains(".png")) {
            mediaType = "image";
        } else {
            // Default: video (ko'p hollarda Instagram videolar)
            mediaType = "video";
        }

        items.add(new MediaItem(downloadUrl, mediaType));
    }

    private String getTextSafe(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            String value = node.get(field).asText();
            return value.isBlank() ? null : value;
        }
        return null;
    }
}
