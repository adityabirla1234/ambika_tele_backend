package com.ambikatelecom.rewards.reporting.application;

import com.ambikatelecom.rewards.reporting.application.MonthlyReportService.TriggerResult;
import com.ambikatelecom.rewards.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs inside Spring Boot every day at 00:05 in the shop's timezone and asks for the month that just ended.
 * Daily is safe: after the first success every later call finds the month SENT and does nothing, and if the
 * server or Telegram was down on the 1st, the next day's tick retries. The database lease and the unique run
 * row mean a restart or a second instance still cannot send a month twice.
 */
@Component
@ConditionalOnProperty(name = "app.reports.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final MonthlyReportService service;

    public ReportScheduler(MonthlyReportService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.reports.cron:0 5 0 * * *}", zone = "${app.shop.timezone}")
    public void daily() {
        try {
            TriggerResult r = service.trigger(null);
            log.info("Monthly report check: month={} outcome={} status={}", r.month(), r.outcome(), r.status());
        } catch (ApiException e) {
            // Not configured yet, or before the configured start month. Nothing to do.
            log.info("Monthly report check skipped: {}", e.getCode());
        } catch (RuntimeException e) {
            log.error("MONTHLY_REPORT_SCHEDULE_ERROR type={}", e.getClass().getName());
        }
    }
}
