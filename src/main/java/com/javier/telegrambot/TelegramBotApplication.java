package com.javier.telegrambot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication
@EnableScheduling
public class TelegramBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                TelegramBotApplication.class,
                args
        );
    }

    @Bean
    public TelegramBotsApi telegramBotsApi(
            TelegramBotService telegramService
    ) throws TelegramApiException {

        TelegramBotsApi botsApi =
                new TelegramBotsApi(DefaultBotSession.class);

        botsApi.registerBot(telegramService);

        return botsApi;
    }
}