package com.javier.telegrambot;

import com.javier.telegrambot.service.InstagramDownloadService;
import com.javier.telegrambot.util.MediaUrlUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class TelegramBotService extends TelegramLongPollingBot {

    private static final int TELEGRAM_MEDIA_GROUP_LIMIT = 10;
    private final TelegramProperties properties;
    private final InstagramDownloadService instagramDownloadService;

    private final String NAV_MESSAGE = """
            📥 Media tayyor!
            ▪️ Yuqori sifat va tezkor yuklash
            ▪️ Cheklovlar yo'q
            👉 Botimiz: @tezda_yuklash_bot
            """;

    public TelegramBotService(TelegramProperties properties, InstagramDownloadService instagramDownloadService) {
        super(properties.getBotToken());
        this.properties = properties;
        this.instagramDownloadService = instagramDownloadService;
    }

    @Override
    public String getBotUsername() {
        return properties.getBotUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String chatId = update.getMessage().getChatId().toString();
        String userText = update.getMessage().getText().trim();

        if (userText.isBlank()) {
            sendTextMessage(chatId, "❌ Link yuboring.");
            return;
        }

        String cleanUrl = MediaUrlUtils.extractCleanUrl(userText);

        if (!MediaUrlUtils.isSupportedUrl(cleanUrl)) {
            sendTextMessage(chatId, "Salom! 👋\nMenga Instagram, YouTube yoki TikTok linkini yuboring.\nMasalan:\n👉 https://www.instagram.com/reel/...\n👉 https://youtu.be/...\n👉 https://vm.tiktok.com/...");
            return;
        }

        Integer loadingMessageId = sendTextMessage(chatId, "⏳ Media qidirilmoqda, biroz kuting...");
        Integer userMessageId = update.getMessage().getMessageId();

        try {
            instagramDownloadService.processUrlAsync(cleanUrl)
                    .thenAccept(mediaItems -> {
                        handleSuccessResult(chatId, mediaItems);
                        deleteMessage(chatId, loadingMessageId);
                        deleteMessage(chatId, userMessageId);
                    })
                    .exceptionally(ex -> {
                        log.error("Media yuklashda xatolik: url={}", cleanUrl, ex);
                        deleteMessage(chatId, loadingMessageId);
                        sendTextMessage(chatId, "⚠️ Media yuklashda xatolik yuz berdi.");
                        return null;
                    });
        } catch (RejectedExecutionException e) {
            log.warn("Queue is full. chatId={}", chatId);
            deleteMessage(chatId, loadingMessageId);
            sendTextMessage(chatId, "⏳ Hozir botda navbat juda katta. Iltimos, birozdan keyin qayta urinib ko'ring.");
        }
    }

    private void handleSuccessResult(String chatId, List<MediaItem> mediaItems) {
        if (mediaItems == null || mediaItems.isEmpty()) {
            sendTextMessage(chatId, "❌ Kechirasiz, bu havoladan media topilmadi.");
            return;
        }
        if (mediaItems.size() == 1) {
            sendSingleMedia(chatId, mediaItems.get(0));
        } else {
            sendMediaGroups(chatId, mediaItems);
        }
    }

    private void sendSingleMedia(String chatId, MediaItem media) {
        // 1-Tezkor Uslub: To'g'ridan to'g'ri URL ni Telegramga berish (Eski, tezkor usul)
        try {
            if (media.isVideo()) {
                SendVideo sendVideo = new SendVideo();
                sendVideo.setChatId(chatId);
                sendVideo.setVideo(new InputFile(media.url()));
                sendVideo.setCaption(NAV_MESSAGE);
                sendVideo.setSupportsStreaming(true);
                execute(sendVideo);
            } else {
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(chatId);
                sendPhoto.setPhoto(new InputFile(media.url()));
                sendPhoto.setCaption(NAV_MESSAGE);
                execute(sendPhoto);
            }
            log.info("Direct URL orqali 1 soniyada yuborildi!");
            return; // Agar muvaffaqiyatli bo'lsa, pastdagi sekin usulga o'tmaymiz
        } catch (TelegramApiException e) {
            log.warn("Telegram direct URL ni qabul qila olmadi (IP blok), sekin-ko'prik usuliga o'tilmoqda...");
        }

        // 2-Kafolatlangan Uslub: (Yangi usul, faylni yuklab, Telegramga berish)
        java.io.File tempFile = null;
        try {
            log.info("Sizning kompyuteringiz orqali Telegramga media jo'natilmoqda...");
            String ext = media.isVideo() ? ".mp4" : ".jpg";
            tempFile = downloadToTempFile(media.url(), ext);
            
            if (media.isVideo()) {
                SendVideo sendVideo = new SendVideo();
                sendVideo.setChatId(chatId);
                sendVideo.setVideo(new InputFile(tempFile));
                sendVideo.setCaption(NAV_MESSAGE);
                sendVideo.setSupportsStreaming(true);
                execute(sendVideo);
            } else {
                SendPhoto sendPhoto = new SendPhoto();
                sendPhoto.setChatId(chatId);
                sendPhoto.setPhoto(new InputFile(tempFile));
                sendPhoto.setCaption(NAV_MESSAGE);
                execute(sendPhoto);
            }
            log.info("Media Telegramga muvaffaqiyatli yuborildi!");
        } catch (Exception e) {
            log.error("Failed to send single media: {}", media.url(), e);
            sendTextMessage(chatId, "❌ Media Telegram'ga yuborilmadi yoki hajmi juda katta.");
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void sendMediaGroups(String chatId, List<MediaItem> mediaItems) {
        for (int start = 0; start < mediaItems.size(); start += TELEGRAM_MEDIA_GROUP_LIMIT) {
            int end = Math.min(start + TELEGRAM_MEDIA_GROUP_LIMIT, mediaItems.size());
            sendMediaGroupBatch(chatId, mediaItems.subList(start, end));
        }
    }

    private void sendMediaGroupBatch(String chatId, List<MediaItem> mediaItems) {
        List<java.io.File> filesToClose = new ArrayList<>();
        try {
            SendMediaGroup mediaGroup = new SendMediaGroup();
            mediaGroup.setChatId(chatId);
            List<InputMedia> medias = new ArrayList<>();

            for (MediaItem item : mediaItems) {
                if (item == null || item.url() == null || item.url().isBlank()) continue;
                
                try {
                    String ext = item.isVideo() ? ".mp4" : ".jpg";
                    java.io.File file = downloadToTempFile(item.url(), ext);
                    filesToClose.add(file);

                    if (item.isVideo()) {
                        InputMediaVideo video = new InputMediaVideo();
                        video.setMedia(file, file.getName());
                        medias.add(video);
                    } else {
                        InputMediaPhoto photo = new InputMediaPhoto();
                        photo.setMedia(file, file.getName());
                        medias.add(photo);
                    }
                } catch (Exception e) {
                    log.warn("Failed to proxy URL for media group item", e);
                }
            }
            if (!medias.isEmpty()) {
                medias.get(0).setCaption(NAV_MESSAGE);
                mediaGroup.setMedias(medias);
                execute(mediaGroup);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to send media group", e);
            sendTextMessage(chatId, "❌ Media albumini Telegram'ga yuborib bo'lmadi (URL stream xatosi).");
        } finally {
            for (java.io.File file : filesToClose) {
                if (file != null && file.exists()) {
                    file.delete();
                }
            }
        }
    }

    private java.io.File downloadToTempFile(String urlStr, String suffix) throws Exception {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.URLConnection conn = url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/119.0.0.0 Safari/537.36");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        
        java.io.File tempFile = java.io.File.createTempFile("tg_media_", suffix);
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    private Integer sendTextMessage(String chatId, String text) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText(text);
            return execute(message).getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send text message", e);
            return null;
        }
    }

    private void deleteMessage(String chatId, Integer messageId) {
        if (messageId == null) return;
        try {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(chatId);
            deleteMessage.setMessageId(messageId);
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to delete message", e);
        }
    }
}