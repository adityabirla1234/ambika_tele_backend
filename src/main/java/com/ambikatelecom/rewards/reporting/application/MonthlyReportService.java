package com.ambikatelecom.rewards.reporting.application;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.customers.persistence.CustomerReportRow;
import com.ambikatelecom.rewards.customers.persistence.CustomerRepository;
import com.ambikatelecom.rewards.reporting.application.TelegramClient.Sent;
import com.ambikatelecom.rewards.reporting.application.TelegramClient.TelegramException;
import com.ambikatelecom.rewards.reporting.domain.AttemptOutcome;
import com.ambikatelecom.rewards.reporting.domain.MonthlyReportAttempt;
import com.ambikatelecom.rewards.reporting.domain.MonthlyReportRun;
import com.ambikatelecom.rewards.reporting.domain.RunStatus;
import com.ambikatelecom.rewards.reporting.persistence.MonthlyReportAttemptRepository;
import com.ambikatelecom.rewards.reporting.persistence.MonthlyReportRunRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Monthly "new customers" PDF to Telegram.
 *
 * Safety model:
 *  - monthly_report_runs is UNIQUE per month, so there is exactly one run row per month.
 *  - A run that is SENT is never sent again (duplicate triggers return ALREADY_SENT).
 *  - The job holds a database lease (guarded UPDATE), so two instances or two overlapping triggers cannot both send.
 *    The lease is renewed before each attempt and an expired lease lets a later trigger recover a crashed job.
 *  - Retries are bounded, with exponential backoff and jitter. Permanent Telegram errors (bad token, bot not in the
 *    channel) stop immediately instead of retrying.
 *  - This class is deliberately NOT @Transactional: no database transaction is held across an HTTP call.
 *
 * Known limit: if Telegram receives the file but the response is lost, the retry can post the PDF a second time.
 * Telegram offers no idempotency key for sendDocument, so this is accepted and documented in the README.
 */
@Service
public class MonthlyReportService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportService.class);
    private static final int PAGE_SIZE = 500;
    private static final String ACTOR = "scheduler";

    public enum TriggerOutcome { STARTED, IN_PROGRESS, ALREADY_SENT }

    public record TriggerResult(String month, TriggerOutcome outcome, RunStatus status) {}

    public record RunView(String month, RunStatus status, Integer rowCount, int attempts, String lastError,
                          Instant startedAt, Instant completedAt) {}

    private record Delivered(long rows, long messageId) {}

    private final ReportSettings settings;
    private final MonthlyReportRunRepository runs;
    private final MonthlyReportAttemptRepository attempts;
    private final CustomerRepository customers;
    private final PdfReportRenderer renderer;
    private final TelegramClient telegram;
    private final AuditService audit;
    private final ExecutorService executor;
    private final Clock clock;

    public MonthlyReportService(ReportSettings settings, MonthlyReportRunRepository runs,
                                MonthlyReportAttemptRepository attempts, CustomerRepository customers,
                                PdfReportRenderer renderer, TelegramClient telegram, AuditService audit,
                                @Qualifier("reportExecutor") ExecutorService executor) {
        this.settings = settings;
        this.runs = runs;
        this.attempts = attempts;
        this.customers = customers;
        this.renderer = renderer;
        this.telegram = telegram;
        this.audit = audit;
        this.executor = executor;
        this.clock = Clock.system(settings.shopZone());
    }

    // ---------------------------------------------------------------- trigger

    /**
     * Called by the external scheduler. Safe to call any number of times: it starts at most one job per month and
     * answers straight away. With no month it reports the month that just ended in the shop's timezone.
     */
    public TriggerResult trigger(String monthText) {
        if (!settings.telegramConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPORTS_DISABLED", "Reports are not configured.");
        }
        ReportPeriod period = resolvePeriod(monthText);
        MonthlyReportRun run = findOrCreate(period);
        if (run.getStatus() == RunStatus.SENT) {
            return new TriggerResult(period.label(), TriggerOutcome.ALREADY_SENT, RunStatus.SENT);
        }

        String owner = UUID.randomUUID().toString();
        Instant now = clock.instant();
        int won = runs.acquireLease(run.getId(), owner, now.plusSeconds(settings.leaseMinutes() * 60),
                now, RunStatus.RUNNING, RunStatus.SENT);
        if (won == 0) {
            RunStatus current = runs.findById(run.getId()).map(MonthlyReportRun::getStatus).orElse(RunStatus.RUNNING);
            TriggerOutcome outcome = current == RunStatus.SENT ? TriggerOutcome.ALREADY_SENT : TriggerOutcome.IN_PROGRESS;
            return new TriggerResult(period.label(), outcome, current);
        }
        attempts.abandonInProgress(run.getId(), now, AttemptOutcome.FAILURE, AttemptOutcome.IN_PROGRESS);

        try {
            executor.execute(() -> runJob(run.getId(), owner, period));
        } catch (RejectedExecutionException e) {
            runs.markFailed(run.getId(), owner, "Server busy, will retry at the next trigger", clock.instant(), RunStatus.FAILED);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", "Busy. Try again shortly.");
        }
        return new TriggerResult(period.label(), TriggerOutcome.STARTED, RunStatus.RUNNING);
    }

    /** Latest runs for the read-only manager screen. No customer data. */
    public List<RunView> recentRuns() {
        return runs.findTop12ByOrderByReportMonthDesc().stream()
                .map(r -> new RunView(r.getReportMonth(), r.getStatus(), r.getRowCount(), r.getAttemptCount(),
                        r.getLastError(), r.getCreatedAt(), r.getCompletedAt()))
                .toList();
    }

    private ReportPeriod resolvePeriod(String text) {
        YearMonth month;
        if (text == null || text.isBlank()) {
            month = YearMonth.now(clock).minusMonths(1);
            if (settings.startMonth() != null && month.isBefore(settings.startMonth())) {
                throw new ApiException(HttpStatus.CONFLICT, "BEFORE_START_MONTH", "Reports start from " + settings.startMonth() + ".");
            }
        } else {
            try {
                month = YearMonth.parse(text.trim());
            } catch (DateTimeParseException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Month must look like 2026-09.", "month");
            }
        }
        ReportPeriod period = ReportPeriod.of(month, settings.shopZone());
        if (!period.isFinished(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MONTH_NOT_FINISHED", "That month has not finished yet.", "month");
        }
        return period;
    }

    private MonthlyReportRun findOrCreate(ReportPeriod period) {
        return runs.findByReportMonth(period.label()).orElseGet(() -> {
            try {
                return runs.saveAndFlush(new MonthlyReportRun(period.label(), period.start(), period.end()));
            } catch (DataIntegrityViolationException e) {
                // A simultaneous trigger created it first. Use that row.
                return runs.findByReportMonth(period.label()).orElseThrow();
            }
        });
    }

    // -------------------------------------------------------------------- job

    private void runJob(Long runId, String owner, ReportPeriod period) {
        MDC.put("correlationId", "report-" + period.label());
        try {
            doJob(runId, owner, period);
        } catch (RuntimeException e) {
            // Something unexpected (for example the database was unreachable). The lease expires on its own and the
            // next trigger recovers the run.
            log.error("MONTHLY_REPORT_JOB_CRASHED month={} type={}", period.label(), e.getClass().getName());
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void doJob(Long runId, String owner, ReportPeriod period) {
        String lastError = "Unknown error";
        boolean interrupted = false;
        int max = settings.maxAttempts();

        for (int n = 1; n <= max; n++) {
            Instant now = clock.instant();
            if (runs.renewLease(runId, owner, now.plusSeconds(settings.leaseMinutes() * 60), now, RunStatus.RUNNING) == 0) {
                log.error("MONTHLY_REPORT_LEASE_LOST month={} (another job took over, stopping)", period.label());
                return;
            }
            MonthlyReportAttempt attempt = attempts.save(
                    new MonthlyReportAttempt(runId, attempts.countByRunId(runId) + 1, now));
            runs.incrementAttempts(runId, now);

            try {
                Delivered d = generateAndSend(period);
                finish(attempt, AttemptOutcome.SUCCESS, null, null);
                int updated = runs.markSent(runId, owner, (int) d.rows(), d.messageId(), clock.instant(), RunStatus.SENT);
                if (updated == 0) {
                    log.error("MONTHLY_REPORT_SENT_BUT_NOT_RECORDED month={} messageId={}", period.label(), d.messageId());
                } else {
                    audit.record("MONTHLY_REPORT_SENT", "MONTHLY_REPORT", runId, ACTOR,
                            "month=" + period.label() + ", rows=" + d.rows());
                    log.info("Monthly report sent month={} rows={} attempt={}", period.label(), d.rows(), n);
                }
                return;
            } catch (TelegramException e) {
                lastError = e.getMessage();
                finish(attempt, AttemptOutcome.FAILURE, lastError, e.httpStatus());
                log.warn("Monthly report attempt {}/{} failed month={} retryable={} error={}",
                        n, max, period.label(), e.isRetryable(), lastError);
                if (!e.isRetryable()) break;
                if (n < max && !pause(backoffMs(n, e.retryAfterSeconds()))) {
                    interrupted = true;
                    lastError = "Interrupted (server restarting)";
                    break;
                }
            } catch (IOException | RuntimeException e) {
                lastError = "Internal error: " + e.getClass().getSimpleName();
                finish(attempt, AttemptOutcome.FAILURE, lastError, null);
                log.warn("Monthly report attempt {}/{} failed month={} type={}", n, max, period.label(), e.getClass().getName());
                if (n < max && !pause(backoffMs(n, null))) {
                    interrupted = true;
                    lastError = "Interrupted (server restarting)";
                    break;
                }
            }
        }

        runs.markFailed(runId, owner, truncate(lastError, 290), clock.instant(), RunStatus.FAILED);
        audit.record("MONTHLY_REPORT_FAILED", "MONTHLY_REPORT", runId, ACTOR, "month=" + period.label());
        log.error("MONTHLY_REPORT_FAILED month={} lastError={}", period.label(), lastError);
        if (!interrupted) {
            telegram.sendAlert("Ambika rewards: the monthly customer report for " + period.label()
                    + " could not be sent. Last error: " + lastError
                    + ". It will be retried automatically at the next scheduled trigger.");
        }
    }

    private Delivered generateAndSend(ReportPeriod period) throws IOException {
        long total = customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(period.start(), period.end());
        Path tmp = Files.createTempFile("ambika-report-", ".pdf"); // owner-only permissions on Linux
        try {
            Instant[] lastAt = {period.start()};
            Long[] lastId = {0L};
            PdfReportRenderer.RowSource source = () -> {
                List<CustomerReportRow> page = customers.findReportPage(
                        period.start(), period.end(), lastAt[0], lastId[0], PageRequest.of(0, PAGE_SIZE));
                if (!page.isEmpty()) {
                    CustomerReportRow last = page.get(page.size() - 1);
                    lastAt[0] = last.getCreatedAt();
                    lastId[0] = last.getId();
                }
                return page;
            };
            renderer.render(tmp, period, ZonedDateTime.now(clock), total, source);
            String caption = "Ambika Telecom: new customers, " + period.humanMonth() + " (" + total + ")";
            Sent sent = telegram.sendDocument(tmp, "new-customers-" + period.label() + ".pdf", caption);
            return new Delivered(total, sent.messageId());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("Could not delete the temporary report file");
            }
        }
    }

    private void finish(MonthlyReportAttempt attempt, AttemptOutcome outcome, String error, Integer http) {
        attempt.finish(outcome, error == null ? null : truncate(error, 290), http, clock.instant());
        attempts.save(attempt);
    }

    /** Exponential backoff with "equal jitter", never shorter than Telegram's own retry_after. */
    long backoffMs(int attemptNo, Long retryAfterSeconds) {
        long exp = Math.min(settings.maxBackoffMs(), settings.initialBackoffMs() << Math.min(attemptNo - 1, 20));
        long jittered = ThreadLocalRandom.current().nextLong(exp / 2, exp + 1);
        if (retryAfterSeconds != null) {
            jittered = Math.max(jittered, Math.min(retryAfterSeconds, 120L) * 1000L);
        }
        return jittered;
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
