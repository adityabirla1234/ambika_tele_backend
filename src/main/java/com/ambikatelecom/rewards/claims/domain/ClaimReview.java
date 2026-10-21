package com.ambikatelecom.rewards.claims.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only decision history. Rows are never updated or deleted. */
@Entity
@Table(name = "claim_reviews", indexes = @Index(name = "ix_reviews_claim", columnList = "claim_id, created_at"))
public class ClaimReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false, updatable = false)
    private Claim claim;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReviewDecision decision;

    /** Required for REJECTED (shown to the customer) and REOPENED. */
    @Column(length = 500, updatable = false)
    private String reason;

    @Column(nullable = false, length = 160, updatable = false)
    private String reviewer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClaimReview() {}

    public ClaimReview(Claim claim, ReviewDecision decision, String reason, String reviewer) {
        this.claim = claim;
        this.decision = decision;
        this.reason = reason;
        this.reviewer = reviewer;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public ReviewDecision getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getReviewer() { return reviewer; }
    public Instant getCreatedAt() { return createdAt; }
}
