package com.ambikatelecom.rewards.audit.application;

import com.ambikatelecom.rewards.audit.domain.AuditLog;
import com.ambikatelecom.rewards.audit.persistence.AuditLogRepository;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Joins the caller's transaction so the audit row commits or rolls back with the business change. */
    public void record(String action, String entityType, Long entityId, String actor, String metadata) {
        repo.save(new AuditLog(action, entityType, entityId, actor, metadata));
    }
}
