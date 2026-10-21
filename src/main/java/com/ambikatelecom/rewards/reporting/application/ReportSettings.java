package com.ambikatelecom.rewards.reporting.application;

import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Report settings from environment variables. Secrets stay here and are never logged or returned by any API. */
@Component
public class ReportSettings {

    private static final Logger log = LoggerFactory.getLogger(ReportSettings.class);


    private final String botToken;
    private final String chatId;
    private final String alertChatId;
    private final String apiBase;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long leaseMinutes;
    private final String fontPath;
    private final ZoneId shopZone;
    private final YearMonth startMonth;

    public ReportSettings(@Value("${app.reports.telegram.bot-token:}") String botToken,
                          @Value("${app.reports.telegram.chat-id:}") String chatId,
                          @Value("${app.reports.telegram.alert-chat-id:}") String alertChatId,
                          @Value("${app.reports.telegram.api-base:https://api.telegram.org}") String apiBase,
                          @Value("${app.reports.max-attempts:4}") int maxAttempts,
                          @Value("${app.reports.initial-backoff-ms:2000}") long initialBackoffMs,
                          @Value("${app.reports.max-backoff-ms:60000}") long maxBackoffMs,
                          @Value("${app.reports.lease-minutes:10}") long leaseMinutes,
                          @Value("${app.reports.font-path:}") String fontPath,
                          @Value("${app.reports.start-month:}") String startMonth,
                          @Value("${app.shop.timezone}") String shopTimezone) {
        this.botToken = botToken.trim();
        this.chatId = chatId.trim();
        this.alertChatId = alertChatId.isBlank() ? this.chatId : alertChatId.trim();
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 8));
        this.initialBackoffMs = Math.max(100, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.leaseMinutes = Math.max(2, leaseMinutes);
        this.fontPath = fontPath.trim();
        this.shopZone = ZoneId.of(shopTimezone);
        this.startMonth = startMonth.isBlank() ? null : YearMonth.parse(startMonth.trim());

    }

    public String botToken() { return botToken; }
    public String chatId() { return chatId; }
    public String alertChatId() { return alertChatId; }
    public String apiBase() { return apiBase; }
    public int maxAttempts() { return maxAttempts; }
    public long initialBackoffMs() { return initialBackoffMs; }
    public long maxBackoffMs() { return maxBackoffMs; }
    public long leaseMinutes() { return leaseMinutes; }
    public String fontPath() { return fontPath; }
    public ZoneId shopZone() { return shopZone; }
    /** Months before this are never sent automatically (so the first night after launch does not send an empty last month). Null = no limit. */
    public YearMonth startMonth() { return startMonth; }

    public boolean telegramConfigured() { return !botToken.isEmpty() && !chatId.isEmpty(); }
}
