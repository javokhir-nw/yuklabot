package com.javier.telegrambot;


public record MediaItem(
        String url,
        String type
) {

    public boolean isVideo() {
        return "video".equalsIgnoreCase(type);
    }

    public boolean isImage() {
        return "image".equalsIgnoreCase(type);
    }
}
