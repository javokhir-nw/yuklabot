package com.javier.telegrambot.repository;

import com.javier.telegrambot.entity.InstagramCookie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstagramCookieRepository extends JpaRepository<InstagramCookie, Long> {
    
    // Find the oldest active cookie by lastUsedAt
    Optional<InstagramCookie> findFirstByIsActiveTrueOrderByLastUsedAtAsc();
    
    // Fetch all active cookies for caching
    List<InstagramCookie> findAllByIsActiveTrue();
}
