package com.ambikatelecom.rewards.rewards.domain;

import com.ambikatelecom.rewards.claims.domain.Claim;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** One reward per claim (UNIQUE claim_id). Holds the range shown to the customer; the shop owner pays the amount. */
@Entity
@Table(name = "rewards", uniqueConstraints = @UniqueConstraint(name = "uk_rewards_claim", columnNames = "claim_id"))
public class Reward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Column(name = "reward_min", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMin;

    @Column(name = "reward_max", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardState state = RewardState.ASSIGNED;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    @Column(name = "paid_amount", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "paid_by", length = 160)
    private String paidBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Reward() {}

    public Reward(Claim claim) {
        this.claim = claim;
        this.rewardMin = claim.getRewardMin();
        this.rewardMax = claim.getRewardMax();
    }

    @PrePersist
    void onCreate() { assignedAt = Instant.now(); }

    /** First reveal only. A repeat call, or a reward already paid, changes nothing. */
    public void markRevealed() {
        if (state != RewardState.ASSIGNED) return;
        state = RewardState.REVEALED;
        revealedAt = Instant.now();
    }

    public void markPaid(BigDecimal amount, String by) {
        if (state == RewardState.REDEEMED) throw new IllegalStateException("Already paid");
        this.state = RewardState.REDEEMED;
        this.paidAmount = amount;
        this.paidBy = by;
        this.paidAt = Instant.now();
    }

    public Long getId() { return id; }
    public RewardState getState() { return state; }
    public BigDecimal getRewardMin() { return rewardMin; }
    public BigDecimal getRewardMax() { return rewardMax; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getPaidBy() { return paidBy; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getRevealedAt() { return revealedAt; }
}
