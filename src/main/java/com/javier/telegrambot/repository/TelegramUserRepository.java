package com.javier.telegrambot.repository;

import com.javier.telegrambot.entity.TelegramUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, String> {
}
