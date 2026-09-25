package com.javier.telegrambot.service;

import com.javier.telegrambot.entity.InstagramCookie;
import com.javier.telegrambot.repository.InstagramCookieRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class InstagramCookieService {

    private final InstagramCookieRepository cookieRepository;
    private final List<InstagramCookie> activeCookies = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public InstagramCookieService(InstagramCookieRepository cookieRepository) {
        this.cookieRepository = cookieRepository;
    }

    /**
     * Dastur yonganda va keyin har 5 daqiqada bazadagi qo'shilgan, o'zgartirilgan 
     * cookielarni hotiraga tortib keshlash
     */
    @PostConstruct
    @Scheduled(fixedRate = 300000) // 5 daqiqa
    public void refreshCookieCache() {
        log.info("Refreshing Instagram cookies cache from DB...");
        List<InstagramCookie> fromDb = cookieRepository.findAllByIsActiveTrue();
        activeCookies.clear();
        activeCookies.addAll(fromDb);
        log.info("Successfully loaded {} active cookies into memory.", activeCookies.size());
    }

    /**
     * Keshlangan listdan navbatdagi(Round Robin) Cookie'ni olib beradi
     */
    public InstagramCookie getNextCookie() {
        if (activeCookies.isEmpty()) {
            return null; // Cookie available emas yoxud hammasi ban yegan
        }
        
        int index = Math.abs(roundRobinIndex.getAndIncrement() % activeCookies.size());
        InstagramCookie cookie = activeCookies.get(index);
        
        // Yangilanish uchun (foydalanish soni++) bazaga saqlab qoyamiz asinxron qilsayam boladi, lekin async emas
        cookie.setUsageCount(cookie.getUsageCount() + 1);
        cookie.setLastUsedAt(LocalDateTime.now());
        cookieRepository.save(cookie);
        
        return cookie;
    }

    /**
     * Agar process/yuklash vaqtida cookie 401 unauth. xato bersa uni keshtdan olib
     * tashlab, databaseda mark as banned qilishimiz kerak
     */
    public void banCookie(InstagramCookie cookie) {
        if (cookie == null) return;
        log.warn("Banning cookie ID: {}", cookie.getId());
        
        // Keshtdan olib taslash
        activeCookies.removeIf(c -> c.getId().equals(cookie.getId()));
        
        // DB da ban yedi qilish
        cookie.setActive(false);
        cookieRepository.save(cookie);
    }
}
