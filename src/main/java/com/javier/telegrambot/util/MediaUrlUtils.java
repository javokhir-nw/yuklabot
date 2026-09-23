package com.javier.telegrambot.util;

public class MediaUrlUtils {

    public static boolean isSupportedUrl(String url) {
        if (url == null) return false;
        
        // Instagram
        if (url.matches("(?i)^https?://(www\\.)?instagram\\.com/(reels?|p|tv|stories|shorts)/.+")) return true;
        // YouTube
        if (url.matches("(?i)^https?://(www\\.)?(youtube\\.com|youtu\\.be)/.+")) return true;
        // TikTok
        if (url.matches("(?i)^https?://(www\\.|vm\\.|vt\\.)?tiktok\\.com/.+")) return true;
        
        return false;
    }

    public static String extractCleanUrl(String userText) {
        String cleaned = userText.trim();
        int spaceIndex = cleaned.indexOf(" ");
        if (spaceIndex != -1) {
            cleaned = cleaned.substring(0, spaceIndex);
        }
        
        // Remove trailing slash if it is just at the end of the base domain 
        // Note: yt-dlp is smart enough, so we don't strictly need to strip query params.
        return cleaned;
    }
}
