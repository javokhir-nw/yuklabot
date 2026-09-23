package com.javier.telegrambot.service;

import com.javier.telegrambot.MediaItem;
import com.javier.telegrambot.util.SimpleRateLimiter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class InstagramDownloadService {

    private static final int MAX_CONCURRENT_TASKS = 4;
    private static final int MAX_QUEUE_SIZE = 100;
    private static final double REQUESTS_PER_SECOND = 1.5;
    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_RETRY_DELAY = 1_000;

    private final ThreadPoolExecutor executorService;
    private final SimpleRateLimiter rateLimiter;
    private final YtDlpClient ytDlpClient;
    private final ConcurrentHashMap<String, Object> urlLocks = new ConcurrentHashMap<>();

    public InstagramDownloadService(YtDlpClient ytDlpClient) {
        this.ytDlpClient = ytDlpClient;
        this.rateLimiter = new SimpleRateLimiter(REQUESTS_PER_SECOND);

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(@NonNull Runnable r) {
                return new Thread(r, "telegram-worker-" + counter.getAndIncrement());
            }
        };

        this.executorService = new ThreadPoolExecutor(
                MAX_CONCURRENT_TASKS, MAX_CONCURRENT_TASKS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_SIZE), threadFactory, new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<List<MediaItem>> processUrlAsync(String url) {
        return CompletableFuture.supplyAsync(() -> {
            Object lock = urlLocks.computeIfAbsent(url, k -> new Object());
            try {
                synchronized (lock) {
                    return resolveWithRetry(url);
                }
            } finally {
                urlLocks.remove(url, lock);
            }
        }, executorService);
    }

    private List<MediaItem> resolveWithRetry(String url) {
        Exception lastException;

        for (int attempt = 1; true; attempt++) {
            try {
                rateLimiter.acquire();
                return ytDlpClient.resolveMedia(url);
            } catch (Exception e) {
                lastException = e;
                log.warn("Attempt {} failed for url {}: {}", attempt, url, e.getMessage());

                if (attempt > MAX_RETRIES) break;

                long baseDelay = INITIAL_RETRY_DELAY * (1L << (attempt - 1));
                long jitter = ThreadLocalRandom.current().nextLong(0, 500);

                try {
                    Thread.sleep(baseDelay + jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }
        throw new RuntimeException("Media processing failed: " + lastException.getMessage(), lastException);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExecutorService...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
}
