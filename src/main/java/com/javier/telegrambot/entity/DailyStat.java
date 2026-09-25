package com.javier.telegrambot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "daily_stats")
public class DailyStat {
    @Id
    private LocalDate date;
    private int newUsers = 0;
    private int downloadedVideos = 0;
    private int downloadedImages = 0;
}
