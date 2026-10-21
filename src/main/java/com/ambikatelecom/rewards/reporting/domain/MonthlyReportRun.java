package com.ambikatelecom.rewards.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One row per calendar month (UNIQUE report_month), so a month can be sent at most once.
 * State changes after creation are done with guarded UPDATE statements (see the repository),
 * which is what makes the lease and the "already sent" check safe across instances.
 */
@Entity
@Table(name = "monthly_report_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_run_month", columnNames = "report_month"))
public class MonthlyReportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** YYYY-MM in the shop's timezone. */
    @Column(name = "report_month", nullable = false, length = 7)
    private String reportMonth;

    /** Half-open UTC interval [periodStart, periodEnd) the report covered. */
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RunStatus status;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_owner", length = 40)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** Short, redacted summary. Never holds a token, a URL or customer data. */
    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected MonthlyReportRun() {}

    public MonthlyReportRun(String reportMonth, Instant periodStart, Instant periodEnd) {
        this.reportMonth = reportMonth;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = RunStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getReportMonth() { return reportMonth; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public RunStatus getStatus() { return status; }
    public Integer getRowCount() { return rowCount; }
    public Long getTelegramMessageId() { return telegramMessageId; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
