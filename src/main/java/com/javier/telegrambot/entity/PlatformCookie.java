package com.javier.telegrambot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "platform_cookies")
public class PlatformCookie {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PlatformType platformType;

    private String username;

    @Column(columnDefinition = "TEXT")
    private String content;

    private boolean isActive = true;
    private LocalDateTime lastUsedAt;
    private int usageCount = 0;
}
