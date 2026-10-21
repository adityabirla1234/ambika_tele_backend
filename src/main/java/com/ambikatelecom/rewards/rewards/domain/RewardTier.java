package com.ambikatelecom.rewards.rewards.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A purchase-price band and the reward range shown for it. Boundaries are inclusive.
 * The shop owner pays the actual amount physically; the system only stores and shows the range.
 */
@Entity
@Table(name = "reward_tiers", indexes = @Index(name = "ix_reward_tiers_range", columnList = "purchase_min, purchase_max"))
public class RewardTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_min", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchaseMin;

    @Column(name = "purchase_max", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchaseMax;

    @Column(name = "reward_min", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMin;

    @Column(name = "reward_max", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMax;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(nullable = false)
    private boolean active = true;

    protected RewardTier() {}

    public RewardTier(BigDecimal purchaseMin, BigDecimal purchaseMax, BigDecimal rewardMin, BigDecimal rewardMax,
                      LocalDate effectiveFrom) {
        this.purchaseMin = purchaseMin;
        this.purchaseMax = purchaseMax;
        this.rewardMin = rewardMin;
        this.rewardMax = rewardMax;
        this.effectiveFrom = effectiveFrom;
    }

    public RewardTier(long purchaseMin, long purchaseMax, long rewardMin, long rewardMax, LocalDate effectiveFrom) {
        this.purchaseMin = BigDecimal.valueOf(purchaseMin);
        this.purchaseMax = BigDecimal.valueOf(purchaseMax);
        this.rewardMin = BigDecimal.valueOf(rewardMin);
        this.rewardMax = BigDecimal.valueOf(rewardMax);
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() { return id; }
    public BigDecimal getPurchaseMin() { return purchaseMin; }
    public BigDecimal getPurchaseMax() { return purchaseMax; }
    public BigDecimal getRewardMin() { return rewardMin; }
    public BigDecimal getRewardMax() { return rewardMax; }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setActive(boolean active) { this.active = active; }

    /** Edits affect new claims only: every claim keeps its own snapshot of the tier it was approved under. */
    public void update(BigDecimal purchaseMin, BigDecimal purchaseMax, BigDecimal rewardMin, BigDecimal rewardMax) {
        this.purchaseMin = purchaseMin;
        this.purchaseMax = purchaseMax;
        this.rewardMin = rewardMin;
        this.rewardMax = rewardMax;
    }
}
