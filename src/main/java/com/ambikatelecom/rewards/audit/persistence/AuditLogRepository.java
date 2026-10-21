package com.ambikatelecom.rewards.audit.persistence;

import com.ambikatelecom.rewards.audit.domain.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityTypeAndEntityIdAndActionOrderByCreatedAtAscIdAsc(String entityType, Long entityId, String action);

    /** Erasure only: the staff reason for a device-number correction is free text, so it is blanked. */
    @Modifying
    @Query(value = "update audit_logs set metadata = '{}' where entity_type = 'CLAIM' and entity_id = :claimId "
            + "and action in ('CLAIM_DEVICE_CORRECTED', 'DEVICE_RELEASED')", nativeQuery = true)
    int scrubDeviceCorrectionReasons(@Param("claimId") Long claimId);
}
