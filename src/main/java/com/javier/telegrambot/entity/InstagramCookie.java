package com.javier.telegrambot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "instagram_cookies")
public class InstagramCookie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    @Column(columnDefinition = "TEXT")
    private String content;

    private boolean isActive = true;

    private LocalDateTime lastUsedAt;

    private Integer usageCount = 0;

    @PrePersist
    protected void onCreate() {
        this.lastUsedAt = LocalDateTime.now();
    }
}
