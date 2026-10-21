package com.ambikatelecom.rewards.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = @Index(name = "ix_audit_entity", columnList = "entity_type, entity_id"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(nullable = false, length = 80)
    private String actor;

    /** Redacted details only. Never store raw device identifiers, phone numbers or tokens here. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(String action, String entityType, Long entityId, String actor, String metadata) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.actor = actor;
        this.metadata = metadata;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public String getActor() { return actor; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
