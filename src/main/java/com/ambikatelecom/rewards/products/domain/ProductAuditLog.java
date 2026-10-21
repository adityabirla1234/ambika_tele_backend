package com.ambikatelecom.rewards.products.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Who changed what and when, for products, price rules and reward tiers. Append-only. */
@Entity
@Table(name = "product_audit_logs", indexes = {
        @Index(name = "ix_pal_product", columnList = "product_id, created_at"),
        @Index(name = "ix_pal_entity", columnList = "entity_type, entity_id")})
public class ProductAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PRODUCT, PRICE_RULE or REWARD_TIER. */
    @Column(name = "entity_type", nullable = false, length = 30, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** Set for product and price-rule events so the product screen can show its own history. */
    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(nullable = false, length = 60, updatable = false)
    private String action;

    @Column(nullable = false, length = 160, updatable = false)
    private String actor;

    /** JSON of old and new values. Product and tier data only, never customer data. */
    @Column(columnDefinition = "TEXT", updatable = false)
    private String changes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductAuditLog() {}

    public ProductAuditLog(String entityType, Long entityId, Long productId, String action, String actor, String changes) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.productId = productId;
        this.action = action;
        this.actor = actor;
        this.changes = changes;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public String getEntityType() { return entityType; }
    public String getAction() { return action; }
    public String getActor() { return actor; }
    public String getChanges() { return changes; }
    public Instant getCreatedAt() { return createdAt; }
}
