package com.ambikatelecom.rewards.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One delivery attempt of a run (a run may take several attempts with backoff). */
@Entity
@Table(name = "monthly_report_attempts",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_attempt", columnNames = {"run_id", "attempt_no"}))
public class MonthlyReportAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AttemptOutcome outcome;

    @Column(name = "error_summary", length = 300)
    private String errorSummary;

    @Column(name = "http_status")
    private Integer httpStatus;

    protected MonthlyReportAttempt() {}

    public MonthlyReportAttempt(Long runId, int attemptNo, Instant startedAt) {
        this.runId = runId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.outcome = AttemptOutcome.IN_PROGRESS;
    }

    public void finish(AttemptOutcome outcome, String errorSummary, Integer httpStatus, Instant at) {
        this.outcome = outcome;
        this.errorSummary = errorSummary;
        this.httpStatus = httpStatus;
        this.finishedAt = at;
    }

    public Long getId() { return id; }
    public int getAttemptNo() { return attemptNo; }
}
