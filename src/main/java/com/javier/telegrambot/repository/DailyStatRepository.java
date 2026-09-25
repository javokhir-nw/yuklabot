package com.javier.telegrambot.repository;

import com.javier.telegrambot.entity.DailyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface DailyStatRepository extends JpaRepository<DailyStat, LocalDate> {
}
