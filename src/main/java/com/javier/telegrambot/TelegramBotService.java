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
    private final com.javier.telegrambot.repository.TelegramUserRepository userRepository;
    private final com.javier.telegrambot.repository.PlatformCookieRepository cookieRepository;
    private final com.javier.telegrambot.service.PlatformCookieService cookieService;
    private final java.util.Map<String, java.util.List<Long>> userRequestTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private final String NAV_MESSAGE = """
            📥 Media tayyor!
            ▪️ Yuqori sifat va tezkor yuklash
            ▪️ Cheklovlar yo'q
            👉 Botimiz: @tezda_yuklash_bot
            """;

    public TelegramBotService(TelegramProperties properties, 
                              InstagramDownloadService instagramDownloadService,
                              com.javier.telegrambot.repository.TelegramUserRepository userRepository,
                              com.javier.telegrambot.repository.PlatformCookieRepository cookieRepository,
                              com.javier.telegrambot.service.PlatformCookieService cookieService) {
        super(properties.getBotToken());
        this.properties = properties;
        this.instagramDownloadService = instagramDownloadService;
        this.userRepository = userRepository;
        this.cookieRepository = cookieRepository;
        this.cookieService = cookieService;
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

        // Baza orqali User ni yangilash / tanish
        com.javier.telegrambot.entity.TelegramUser user = userRepository.findById(chatId).orElseGet(() -> {
            com.javier.telegrambot.entity.TelegramUser newUser = new com.javier.telegrambot.entity.TelegramUser();
            newUser.setChatId(chatId);
            newUser.setUsername(update.getMessage().getFrom().getUserName());
            newUser.setFirstName(update.getMessage().getFrom().getFirstName());
            newUser.setJoinedAt(java.time.LocalDateTime.now());
            newUser.setAdmin(false); // Yangi a'zolar by default admin emas
            return userRepository.save(newUser);
        });

        // Agar u ADMIN bo'lsa va /buyruq bergan bo'lsa
        if (user.isAdmin() && userText.startsWith("/")) {
            handleAdminCommand(chatId, userText);
            return;
        }

        // Agar admin bo'lmasa 1 daqiqalik cheklov (Rate limit)
        if (!user.isAdmin() && isRateLimited(chatId)) {
            sendTextMessage(chatId, "⚠️ 1 daqiqada 5 ta link yuklay olasiz. Iltimos, biroz kutib turing!");
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

    private boolean isRateLimited(String chatId) {
        long now = System.currentTimeMillis();
        userRequestTimes.compute(chatId, (id, times) -> {
            if (times == null) times = new ArrayList<>();
            times.removeIf(t -> now - t > 60000); // Oxirgi 60 sekunddan eskilarni tozalash
            return times;
        });

        if (userRequestTimes.get(chatId).size() >= 5) {
            return true;
        }
        userRequestTimes.get(chatId).add(now);
        return false;
    }

    private void handleAdminCommand(String chatId, String text) {
        if (text.equals("/stat")) {
            long totalUsers = userRepository.count();
            sendTextMessage(chatId, "📊 Bot statistikasi:\nJami yig'ilgan foydalanuvchilar: " + totalUsers + " ta");
        } else if (text.startsWith("/addcookie ")) {
            String[] parts = text.split(" ", 3);
            if (parts.length < 3) {
                sendTextMessage(chatId, "⚠️ Xato format! Bunday kiriting:\n/addcookie instagram [matn]\n/addcookie youtube [matn]");
                return;
            }
            com.javier.telegrambot.entity.PlatformType pType;
            try {
                pType = com.javier.telegrambot.entity.PlatformType.valueOf(parts[1].toUpperCase());
            } catch (Exception e) {
                sendTextMessage(chatId, "⚠️ Tur noto'g'ri! Faqat INSTAGRAM, YOUTUBE, TIKTOK foydalaning.");
                return;
            }
            com.javier.telegrambot.entity.PlatformCookie cookie = new com.javier.telegrambot.entity.PlatformCookie();
            cookie.setUsername("admin_" + pType + "_" + System.currentTimeMillis());
            cookie.setContent(parts[2].trim());
            cookie.setPlatformType(pType);
            cookie.setActive(true);
            cookie.setUsageCount(0);
            cookie.setLastUsedAt(java.time.LocalDateTime.now());
            cookieRepository.save(cookie);
            cookieService.refreshCookieCache();
            sendTextMessage(chatId, "✅ " + pType + " Kukisi muvaffaqiyatli qo'shildi va kesh yangilandi!");
        } else if (text.equals("/cookies")) {
            long total = cookieRepository.count();
            sendTextMessage(chatId, "🍪 Jami yozilgan kukilar soni: " + total + " ta");
        } else if (text.startsWith("/send ")) {
            String message = text.substring("/send ".length()).trim();
            sendTextMessage(chatId, "⏳ Xabarni hammaga yubormoqdaman, jarayon boshlandi...");
            java.util.List<com.javier.telegrambot.entity.TelegramUser> users = userRepository.findAll();
            int success = 0;
            for (com.javier.telegrambot.entity.TelegramUser u : users) {
                if (sendTextMessage(u.getChatId(), message) != null) {
                    success++;
                }
            }
            sendTextMessage(chatId, "✅ Tarqatish tugadi: " + success + "/" + users.size() + " kishiga yetib bordi.");
        } else {
            sendTextMessage(chatId, "⚒ Admin buyruqlari:\n/stat - Umumiy a'zolar soni\n/addcookie [tur] [matn] - Yangi Kuki qo'shish\n/cookies - Jami kukilar soni\n/send [matn] - Reklama tarqatish");
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