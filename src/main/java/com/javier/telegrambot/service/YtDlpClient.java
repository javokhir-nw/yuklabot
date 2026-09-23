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

@Slf4j
@Service
public class YtDlpClient {

    private final ObjectMapper objectMapper;
    // Loyiha joriy papkasidagi yt-dlp.exe manziliga yo'l (Windows muhiti yodda tutilgan)
    private static final String YT_DLP_COMMAND = "yt-dlp";

    public YtDlpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MediaItem> resolveMedia(String url) {
        try {
            log.info("Processing URL via yt-dlp: {}", url);
            // Buyruq qatori: yt-dlp.exe -J --no-warnings "url"
            ProcessBuilder pb = new ProcessBuilder(
                    YT_DLP_COMMAND,
                    "-J",
                    "--no-warnings",
                    url
            );

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("yt-dlp execution timed out after 60 seconds");
                throw new RuntimeException("yt-dlp execution timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("yt-dlp failed with exit code {}. Error: {}", exitCode, errorOutput);
                throw new RuntimeException("yt-dlp execution failed");
            }

            String jsonOutput = output.toString();
            if (jsonOutput.isBlank()) {
                log.error("yt-dlp returned empty output");
                return Collections.emptyList();
            }

            JsonNode rootNode = objectMapper.readTree(jsonOutput);
            return parseResponse(rootNode);

        } catch (Exception e) {
            log.error("Failed to resolve media using yt-dlp API", e);
            throw new RuntimeException("Media processing failed", e);
        }
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
        
        String downloadUrl = node.has("url") ? node.get("url").asText() : null;
        
        if (downloadUrl == null || downloadUrl.isBlank()) {
            if (node.has("requested_downloads") && node.get("requested_downloads").isArray()) {
                JsonNode requested = node.get("requested_downloads");
                if (requested.size() > 0 && requested.get(0).has("url")) {
                    downloadUrl = requested.get(0).get("url").asText();
                }
            }
        }

        if (downloadUrl != null && !downloadUrl.isBlank()) {
            String ext = node.has("ext") ? node.get("ext").asText() : "";
            if (ext.isBlank()) {
                ext = downloadUrl.contains(".jpg") || downloadUrl.contains(".webp") ? "image" : "video";
            }
            items.add(new MediaItem(downloadUrl, ext.equalsIgnoreCase("mp4") || ext.equalsIgnoreCase("video") ? "video" : "image"));
        }
    }
}
