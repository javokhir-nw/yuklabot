package com.javier.telegrambot.repository;

import com.javier.telegrambot.entity.PlatformCookie;
import com.javier.telegrambot.entity.PlatformType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlatformCookieRepository extends JpaRepository<PlatformCookie, Long> {
    List<PlatformCookie> findByIsActiveTrue();
    List<PlatformCookie> findByPlatformTypeAndIsActiveTrueOrderByLastUsedAtAsc(PlatformType platformType);
}
