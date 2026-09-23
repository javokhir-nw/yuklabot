package com.javier.telegrambot.util;

import java.util.concurrent.TimeUnit;

public class SimpleRateLimiter {
    private final long intervalNanos;
    private long nextAllowedNanos;

    public SimpleRateLimiter(double requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be > 0");
        }
        this.intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / requestsPerSecond);
        this.nextAllowedNanos = System.nanoTime();
    }

    public void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long allowed = Math.max(now, nextAllowedNanos);
            nextAllowedNanos = allowed + intervalNanos;
            waitNanos = allowed - now;
        }
        if (waitNanos <= 0) return;

        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rate limiter interrupted", e);
        }
    }
}