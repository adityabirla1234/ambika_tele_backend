package com.ambikatelecom.rewards.reporting.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One worker thread and a tiny queue. The trigger endpoint answers 202 straight away and the job runs here,
 * so a slow Telegram call or a retry backoff never holds the scheduler's HTTP request open.
 */
@Configuration
@EnableScheduling
public class ReportExecutorConfig {

    @Bean(name = "reportExecutor", destroyMethod = "shutdownNow")
    ExecutorService reportExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(2), r -> {
            Thread t = new Thread(r, "monthly-report");
            t.setDaemon(false);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
