package com.javier.telegrambot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "telegram_users")
public class TelegramUser {
    @Id
    private String chatId;
    private String username;
    private String firstName;
    private LocalDateTime joinedAt;
    private boolean isAdmin;
}
