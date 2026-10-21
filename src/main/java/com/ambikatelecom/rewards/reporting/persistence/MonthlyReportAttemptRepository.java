package com.ambikatelecom.rewards.reporting.persistence;

import com.ambikatelecom.rewards.reporting.domain.AttemptOutcome;
import com.ambikatelecom.rewards.reporting.domain.MonthlyReportAttempt;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MonthlyReportAttemptRepository extends JpaRepository<MonthlyReportAttempt, Long> {

    int countByRunId(Long runId);

    /** Attempts left IN_PROGRESS by a crashed or restarted job are closed when a new job takes the lease. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MonthlyReportAttempt a set a.outcome = :failure, a.finishedAt = :now, "
            + "a.errorSummary = 'Interrupted (server restarted or lease expired)' "
            + "where a.runId = :runId and a.outcome = :inProgress")
    int abandonInProgress(@Param("runId") Long runId, @Param("now") Instant now,
                          @Param("failure") AttemptOutcome failure, @Param("inProgress") AttemptOutcome inProgress);
}
