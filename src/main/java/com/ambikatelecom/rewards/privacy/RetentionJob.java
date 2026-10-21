package com.ambikatelecom.rewards.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs RetentionService every night at 02:30 shop time (app.retention.cron). Safe to repeat: erased claims are skipped. */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService service;

    public RetentionJob(RetentionService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 2 * * *}", zone = "${app.shop.timezone}")
    public void nightly() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("RETENTION_RUN_FAILED type={}", e.getClass().getName());
        }
    }
}
