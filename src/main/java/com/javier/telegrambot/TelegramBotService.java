package com.javier.telegrambot;

import com.javier.telegrambot.entity.DailyStat;
import com.javier.telegrambot.entity.PlatformCookie;
import com.javier.telegrambot.entity.PlatformType;
import com.javier.telegrambot.entity.TelegramUser;
import com.javier.telegrambot.repository.DailyStatRepository;
import com.javier.telegrambot.repository.PlatformCookieRepository;
import com.javier.telegrambot.repository.TelegramUserRepository;
import com.javier.telegrambot.service.InstagramDownloadService;
import com.javier.telegrambot.service.PlatformCookieService;
import com.javier.telegrambot.util.MediaUrlUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class TelegramBotService extends TelegramLongPollingBot {

    private static final int TELEGRAM_MEDIA_GROUP_LIMIT = 10;
    private final TelegramProperties properties;
    private final InstagramDownloadService instagramDownloadService;
    private final TelegramUserRepository userRepository;
    private final PlatformCookieRepository cookieRepository;
    private final PlatformCookieService cookieService;
    private final DailyStatRepository dailyStatRepository;
    
    private final Map<String, List<Long>> userRequestTimes = new ConcurrentHashMap<>();
    private final Map<String, AdminState> adminStates = new ConcurrentHashMap<>();

    private final String NAV_MESSAGE = "📥 Media tayyor!\n▪️ Yuqori sifat va tezkor yuklash\n▪️ Cheklovlar yo'q\n👉 Botimiz: @tezda_yuklash_bot";

    public TelegramBotService(TelegramProperties properties, 
                              InstagramDownloadService instagramDownloadService,
                              TelegramUserRepository userRepository,
                              PlatformCookieRepository cookieRepository,
                              PlatformCookieService cookieService,
                              DailyStatRepository dailyStatRepository) {
        super(properties.getBotToken());
        this.properties = properties;
        this.instagramDownloadService = instagramDownloadService;
        this.userRepository = userRepository;
        this.cookieRepository = cookieRepository;
        this.cookieService = cookieService;
        this.dailyStatRepository = dailyStatRepository;
    }

    @Override
    public String getBotUsername() {
        return properties.getBotUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) return;

        String chatId = update.getMessage().getChatId().toString();
        String userText = update.getMessage().hasText() ? update.getMessage().getText().trim() : "";

        // Foydalanuvchini olish yoki yangisini yaratish
        TelegramUser user = userRepository.findById(chatId).orElse(null);
        if (user == null) {
            user = new TelegramUser();
            user.setChatId(chatId);
            user.setUsername(update.getMessage().getFrom().getUserName());
            user.setFirstName(update.getMessage().getFrom().getFirstName());
            user.setJoinedAt(LocalDateTime.now());
            user.setAdmin(false);
            userRepository.save(user);
            incrementStat("newUser");
        }

        // --- ADMIN menyu logikasi ---
        if (user.isAdmin()) {
            if ("🔙 Bekor qilish".equals(userText) || "/admin".equals(userText) || "/start".equals(userText)) {
                adminStates.remove(chatId);
                sendMenu(chatId, "👨‍💻 Admin menyusiga xush kelibsiz:", getAdminMenu());
                return;
            }

            AdminState state = adminStates.getOrDefault(chatId, AdminState.NONE);

            if (state == AdminState.NONE) {
                if ("📊 Statistika".equals(userText) || "/stat".equals(userText)) {
                    sendStat(chatId);
                    return;
                } else if ("📣 Rassilka (Reklama)".equals(userText) || "/send".equals(userText)) {
                    adminStates.put(chatId, AdminState.AWAITING_BROADCAST);
                    sendMenu(chatId, "📣 Tarqatiladigan reklamani (post, rasm yoki video matn bilan) xuddi shu yerga jo'nating:", getCancelMenu());
                    return;
                } else if ("🍪 Kuki qo'shish".equals(userText) || "/addcookie".equals(userText)) {
                    adminStates.put(chatId, AdminState.AWAITING_COOKIE_PLATFORM);
                    sendMenu(chatId, "Qaysi platforma kukisini qo'shmoqchisiz?", getCookiePlatformMenu());
                    return;
                } else if ("🗂 Barcha kukilar".equals(userText) || "/cookies".equals(userText)) {
                    long total = cookieRepository.count();
                    sendMenu(chatId, "🍪 Jami yozilgan kukilar soni: " + total + " ta", getAdminMenu());
                    return;
                }
            } else if (state == AdminState.AWAITING_BROADCAST) {
                sendMenu(chatId, "⏳ Xabarlarni hammaga tarqatish jarayoni fonda boshlandi, kuting...", getAdminMenu());
                broadcastMessage(chatId, update.getMessage());
                adminStates.remove(chatId);
                return;
            } else if (state == AdminState.AWAITING_COOKIE_PLATFORM) {
                PlatformType pType = PlatformType.UNKNOWN;
                if ("📸 Instagram".equals(userText)) pType = PlatformType.INSTAGRAM;
                else if ("▶️ YouTube".equals(userText)) pType = PlatformType.YOUTUBE;
                else if ("🎵 TikTok".equals(userText)) pType = PlatformType.TIKTOK;
                
                if (pType == PlatformType.UNKNOWN) {
                    sendMenu(chatId, "⚠️ Faqat pastdagi menyudagi tugmalardan tanlang:", getCookiePlatformMenu());
                    return;
                }
                adminStates.put(chatId, AdminState.valueOf("AWAITING_COOKIE_" + pType.name()));
                sendMenu(chatId, "Fayl ichidagi tayyorlangan kuki matnlarini huddi shu yerga xabar sifatida jo'nating:", getCancelMenu());
                return;
            } else if (state.name().startsWith("AWAITING_COOKIE_")) {
                if (update.getMessage().hasText()) {
                    PlatformType pType = PlatformType.valueOf(state.name().replace("AWAITING_COOKIE_", ""));
                    PlatformCookie cookie = new PlatformCookie();
                    cookie.setUsername("admin_" + pType + "_" + System.currentTimeMillis());
                    cookie.setContent(userText);
                    cookie.setPlatformType(pType);
                    cookie.setActive(true);
                    cookie.setUsageCount(0);
                    cookie.setLastUsedAt(LocalDateTime.now());
                    cookieRepository.save(cookie);
                    cookieService.refreshCookieCache();
                    
                    adminStates.remove(chatId);
                    sendMenu(chatId, "✅ " + pType + " kuki bazaga saqlandi va darhol ishga tushdi!", getAdminMenu());
                } else {
                    sendMenu(chatId, "⚠️ Faqat matnli kuki jo'nating:", getCancelMenu());
                }
                return;
            }
        } // --- Admin menyu logikasi tugadi ---

        // Oddiy foydalanuvchilar xatolari
        if (userText.isBlank()) {
            if (!user.isAdmin()) {
               sendTextMessage(chatId, "❌ Link yuboring.");
            }
            return;
        }
        if ("/start".equals(userText)) {
            sendTextMessage(chatId, "Salom! 👋\nMenga Instagram, YouTube yoki TikTok linkini yuboring.");
            return;
        }

        // Cheklov tekshirish
        if (!user.isAdmin() && isRateLimited(chatId)) {
            sendTextMessage(chatId, "⚠️ 1 daqiqada faqat 5 ta link yuklay olasiz. Iltimos, biroz cheklov bitishini kuting!");
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
            sendTextMessage(chatId, "❌ Kechirasiz, bu havoladan ochiq media topilmadi.");
            return;
        }
        if (mediaItems.size() == 1) {
            sendSingleMedia(chatId, mediaItems.get(0));
        } else {
            sendMediaGroups(chatId, mediaItems);
        }
    }

    private void sendSingleMedia(String chatId, MediaItem media) {
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
            incrementStat(media.isVideo() ? "video" : "image");
            log.info("Direct URL orqali 1 soniyada yuborildi!");
            return; 
        } catch (TelegramApiException e) {
            log.warn("Telegram direct URL ni qabul qila olmadi (IP blok), sekin-ko'prik usuliga o'tilmoqda...");
        }

        File tempFile = null;
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
            incrementStat(media.isVideo() ? "video" : "image");
            log.info("Media Telegramga muvaffaqiyatli yuborildi!");
        } catch (Exception e) {
            log.error("Failed to send single media: {}", media.url(), e);
            sendTextMessage(chatId, "❌ Media Telegram'ga yuborilmadi yoki hajmi juda katta.");
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    private void sendMediaGroups(String chatId, List<MediaItem> mediaItems) {
        for (int start = 0; start < mediaItems.size(); start += TELEGRAM_MEDIA_GROUP_LIMIT) {
            int end = Math.min(start + TELEGRAM_MEDIA_GROUP_LIMIT, mediaItems.size());
            sendMediaGroupBatch(chatId, mediaItems.subList(start, end));
        }
    }

    private void sendMediaGroupBatch(String chatId, List<MediaItem> mediaItems) {
        List<File> filesToClose = new ArrayList<>();
        try {
            SendMediaGroup mediaGroup = new SendMediaGroup();
            mediaGroup.setChatId(chatId);
            List<InputMedia> medias = new ArrayList<>();

            for (MediaItem item : mediaItems) {
                if (item == null || item.url() == null || item.url().isBlank()) continue;
                try {
                    String ext = item.isVideo() ? ".mp4" : ".jpg";
                    File file = downloadToTempFile(item.url(), ext);
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
                    incrementStat(item.isVideo() ? "video" : "image");
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
            for (File file : filesToClose) {
                if (file != null && file.exists()) file.delete();
            }
        }
    }

    private File downloadToTempFile(String urlStr, String suffix) throws Exception {
        URL url = new URL(urlStr);
        URLConnection conn = url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/119.0.0.0 Safari/537.36");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        
        File tempFile = File.createTempFile("tg_media_", suffix);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    private Integer sendTextMessage(String chatId, String text) {
        return sendMenu(chatId, text, null);
    }

    private Integer sendMenu(String chatId, String text, ReplyKeyboardMarkup keyboard) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText(text);
            if (keyboard != null) {
                message.setReplyMarkup(keyboard);
            }
            return execute(message).getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
            return null;
        }
    }

    private boolean isRateLimited(String chatId) {
        long now = System.currentTimeMillis();
        userRequestTimes.compute(chatId, (id, times) -> {
            if (times == null) times = new ArrayList<>();
            times.removeIf(t -> now - t > 60000);
            return times;
        });
        if (userRequestTimes.get(chatId).size() >= 5) {
            return true;
        }
        userRequestTimes.get(chatId).add(now);
        return false;
    }

    private void sendStat(String chatId) {
        long totalUsers = userRepository.count();
        LocalDate today = LocalDate.now();
        DailyStat stat = dailyStatRepository.findById(today).orElse(null);
        int newUsr = stat != null ? stat.getNewUsers() : 0;
        int vids = stat != null ? stat.getDownloadedVideos() : 0;
        int imgs = stat != null ? stat.getDownloadedImages() : 0;
        
        String msg = String.format("""
                📊 Bot statistikasi:
                
                👥 Jami obunachilar: %d ta
                
                📅 Bugungi ko'rsatkichlar:
                👤 Yangi qo'shilganlar: %d ta
                🎥 Yuklangan videolar: %d marta
                📸 Yuklangan rasmlar: %d marta
                """, totalUsers, newUsr, vids, imgs);
                
        sendMenu(chatId, msg, getAdminMenu());
    }

    private void incrementStat(String type) {
        try {
            LocalDate today = LocalDate.now();
            DailyStat stat = dailyStatRepository.findById(today).orElseGet(() -> {
                DailyStat s = new DailyStat();
                s.setDate(today);
                return s;
            });
            if ("newUser".equals(type)) stat.setNewUsers(stat.getNewUsers() + 1);
            else if ("video".equals(type)) stat.setDownloadedVideos(stat.getDownloadedVideos() + 1);
            else if ("image".equals(type)) stat.setDownloadedImages(stat.getDownloadedImages() + 1);
            dailyStatRepository.save(stat);
        } catch (Exception e) {
            log.error("Failed to increment stats", e);
        }
    }

    private void broadcastMessage(String adminChatId, Message originalMessage) {
        if (originalMessage == null) return;
        List<TelegramUser> users = userRepository.findAll();
        new Thread(() -> {
            int success = 0;
            for (TelegramUser u : users) {
                try {
                    CopyMessage cm = new CopyMessage();
                    cm.setChatId(u.getChatId());
                    cm.setFromChatId(adminChatId);
                    cm.setMessageId(originalMessage.getMessageId());
                    execute(cm);
                    success++;
                    Thread.sleep(40); // Telegram limitlarini buzmaslik uchun
                } catch (Exception ignored) {}
            }
            sendTextMessage(adminChatId, "✅ Reklama to'liq jarayoni tugadi!\nJami a'zolar: " + users.size() + "\nMuvaffaqiyatli yetib bordi: " + success + " ta foydalanuvchiga.");
        }).start();
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

    // -- MENU TUGMALARI YASASH QISMI --
    private ReplyKeyboardMarkup getAdminMenu() {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Statistika");
        row1.add("📣 Rassilka (Reklama)");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🍪 Kuki qo'shish");
        row2.add("🗂 Barcha kukilar");

        keyboardMarkup.setKeyboard(List.of(row1, row2));
        return keyboardMarkup;
    }

    private ReplyKeyboardMarkup getCancelMenu() {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow();
        row1.add("🔙 Bekor qilish");
        keyboardMarkup.setKeyboard(List.of(row1));
        return keyboardMarkup;
    }

    private ReplyKeyboardMarkup getCookiePlatformMenu() {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📸 Instagram");
        row1.add("▶️ YouTube");
        row1.add("🎵 TikTok");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Bekor qilish");
        keyboardMarkup.setKeyboard(List.of(row1, row2));
        return keyboardMarkup;
    }
}