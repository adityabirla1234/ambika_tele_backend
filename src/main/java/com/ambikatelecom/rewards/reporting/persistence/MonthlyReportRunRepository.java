package com.ambikatelecom.rewards.reporting.persistence;

import com.ambikatelecom.rewards.reporting.domain.MonthlyReportRun;
import com.ambikatelecom.rewards.reporting.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state change is a single guarded UPDATE, so two instances (or a duplicate trigger) can never both win.
 * Each runs in its own short transaction; no transaction is ever held open across an HTTP call.
 */
public interface MonthlyReportRunRepository extends JpaRepository<MonthlyReportRun, Long> {

    Optional<MonthlyReportRun> findByReportMonth(String reportMonth);

    List<MonthlyReportRun> findTop12ByOrderByReportMonthDesc();

    /** Wins only if the run is not SENT and nobody holds an unexpired lease. Returns 1 for the winner, 0 otherwise. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportRun r set r.status = :running, r.leaseOwner = :owner, r.leaseExpiresAt = :expires, "
            + "r.updatedAt = :now where r.id = :id and r.status <> :sent "
            + "and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now)")
    int acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("expires") Instant expires,
                     @Param("now") Instant now, @Param("running") RunStatus running, @Param("sent") RunStatus sent);

    /** Extends the lease only for the current owner. 0 means the lease was lost and the job must stop. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportRun r set r.leaseExpiresAt = :expires, r.updatedAt = :now "
            + "where r.id = :id and r.leaseOwner = :owner and r.status = :running")
    int renewLease(@Param("id") Long id, @Param("owner") String owner, @Param("expires") Instant expires,
                   @Param("now") Instant now, @Param("running") RunStatus running);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportRun r set r.status = :sent, r.rowCount = :rows, r.telegramMessageId = :messageId, "
            + "r.completedAt = :now, r.updatedAt = :now, r.lastError = null, r.leaseOwner = null, r.leaseExpiresAt = null "
            + "where r.id = :id and r.leaseOwner = :owner")
    int markSent(@Param("id") Long id, @Param("owner") String owner, @Param("rows") int rows,
                 @Param("messageId") long messageId, @Param("now") Instant now, @Param("sent") RunStatus sent);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportRun r set r.status = :failed, r.lastError = :error, r.updatedAt = :now, "
            + "r.leaseOwner = null, r.leaseExpiresAt = null where r.id = :id and r.leaseOwner = :owner")
    int markFailed(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error,
                   @Param("now") Instant now, @Param("failed") RunStatus failed);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportRun r set r.attemptCount = r.attemptCount + 1, r.updatedAt = :now where r.id = :id")
    int incrementAttempts(@Param("id") Long id, @Param("now") Instant now);
}
