package com.javier.telegrambot.service;

import com.javier.telegrambot.entity.PlatformCookie;
import com.javier.telegrambot.entity.PlatformType;
import com.javier.telegrambot.repository.PlatformCookieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformCookieService {
    private final PlatformCookieRepository repository;
    
    private final Map<PlatformType, List<PlatformCookie>> activeCookiesMap = new ConcurrentHashMap<>();
    private final Map<PlatformType, AtomicInteger> currentIndexMap = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 300000) 
    @Transactional(readOnly = true)
    public void refreshCookieCache() {
        log.info("Refreshing platform cookies cache from DB...");
        List<PlatformCookie> dbCookies = repository.findByIsActiveTrue();
        
        Map<PlatformType, List<PlatformCookie>> grouped = dbCookies.stream()
                .collect(Collectors.groupingBy(PlatformCookie::getPlatformType));

        for (PlatformType type : PlatformType.values()) {
            activeCookiesMap.put(type, grouped.getOrDefault(type, List.of()));
            currentIndexMap.putIfAbsent(type, new AtomicInteger(0));
        }
        
        log.info("Loaded platform cookies: {}", grouped.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().size())
                .collect(Collectors.joining(", ")));
    }

    public synchronized PlatformCookie getNextCookie(PlatformType type) {
        List<PlatformCookie> cookies = activeCookiesMap.getOrDefault(type, List.of());
        if (cookies.isEmpty()) {
            return null;
        }
        AtomicInteger idx = currentIndexMap.get(type);
        int current = idx.getAndUpdate(i -> (i + 1) % cookies.size());
        return cookies.get(current);
    }

    @Transactional
    public void updateUsage(Long id) {
        repository.findById(id).ifPresent(cookie -> {
            cookie.setUsageCount(cookie.getUsageCount() + 1);
            cookie.setLastUsedAt(LocalDateTime.now());
            repository.save(cookie);
        });
    }

    @Transactional
    public void banCookie(Long id) {
        repository.findById(id).ifPresent(cookie -> {
            cookie.setActive(false);
            repository.save(cookie);
            log.warn("Platform Cookie ID={} has been banned!", id);
        });
        refreshCookieCache(); 
    }
}
