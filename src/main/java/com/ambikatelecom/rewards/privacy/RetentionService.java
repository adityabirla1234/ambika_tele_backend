package com.ambikatelecom.rewards.privacy;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.idempotency.persistence.IdempotencyRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled retention routine (RetentionJob calls it once a day).
 *
 * 1. Idempotency records older than app.retention.idempotency-days are deleted. A retried request only needs its key for minutes.
 * 2. Personal data on claims older than app.retention.personal-data-months is erased (see ClaimErasureService).
 *    0 months turns this step off. With app.retention.dry-run=true (the default) it only COUNTS and logs what it would erase.
 *
 * Counts only are logged, never values. At most app.retention.max-per-run claims are handled per run; the rest wait for the next day.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    public record Result(int idempotencyPurged, boolean dryRun, int candidates, int erased, int skipped, int failed) {}

    private final ClaimRepository claims;
    private final IdempotencyRecordRepository idempotency;
    private final ClaimErasureService erasure;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final int personalDataMonths;
    private final int idempotencyDays;
    private final boolean dryRun;
    private final int batchSize;
    private final int maxPerRun;

    public RetentionService(ClaimRepository claims, IdempotencyRecordRepository idempotency, ClaimErasureService erasure,
                            AuditService audit, PlatformTransactionManager tm,
                            @Value("${app.shop.timezone}") String timezone,
                            @Value("${app.retention.personal-data-months:24}") int personalDataMonths,
                            @Value("${app.retention.idempotency-days:7}") int idempotencyDays,
                            @Value("${app.retention.dry-run:true}") boolean dryRun,
                            @Value("${app.retention.batch-size:200}") int batchSize,
                            @Value("${app.retention.max-per-run:2000}") int maxPerRun) {
        this.claims = claims;
        this.idempotency = idempotency;
        this.erasure = erasure;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.zone = ZoneId.of(timezone);
        this.personalDataMonths = personalDataMonths;
        this.idempotencyDays = idempotencyDays;
        this.dryRun = dryRun;
        this.batchSize = Math.max(1, batchSize);
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    public Result run() {
        int purged = 0;
        if (idempotencyDays > 0) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(idempotencyDays));
            Integer n = tx.execute(status -> idempotency.deleteOlderThan(cutoff));
            purged = n == null ? 0 : n;
        }

        int candidates = 0, erased = 0, skipped = 0, failed = 0;
        if (personalDataMonths > 0) {
            Instant cutoff = ZonedDateTime.now(zone).minusMonths(personalDataMonths).toInstant();
            long lastId = 0;
            boolean more = true;
            while (more && candidates < maxPerRun) {
                List<Long> ids = claims.findRetentionCandidateIds(lastId, cutoff, PageRequest.of(0, batchSize));
                if (ids.isEmpty()) break;
                for (Long id : ids) {
                    if (candidates >= maxPerRun) { more = false; break; }
                    candidates++;
                    lastId = id;
                    if (dryRun) continue;
                    try {
                        ClaimErasureService.Outcome o = erasure.erase(id, "system", "retention");
                        if (o == ClaimErasureService.Outcome.ERASED) erased++; else skipped++;
                    } catch (RuntimeException e) {
                        failed++; // type only: the message could name a customer
                        log.error("RETENTION_CLAIM_FAILED type={}", e.getClass().getName());
                    }
                }
            }
        }
        Result r = new Result(purged, dryRun, candidates, erased, skipped, failed);
        log.info("RETENTION_RUN idempotencyPurged={} personalDataMonths={} dryRun={} candidates={} erased={} skipped={} failed={}",
                purged, personalDataMonths, dryRun, candidates, erased, skipped, failed);
        if (!dryRun && (erased > 0 || purged > 0)) {
            audit.record("RETENTION_RUN", "SYSTEM", null, "system",
                    "{\"idempotencyPurged\":" + purged + ",\"claimsErased\":" + erased + ",\"failed\":" + failed + "}");
        }
        return r;
    }
}
