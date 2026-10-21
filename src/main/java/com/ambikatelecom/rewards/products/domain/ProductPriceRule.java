package com.ambikatelecom.rewards.products.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Product-specific eligible price bounds. History is kept by adding rows, not editing. */
@Entity
@Table(name = "product_price_rules", indexes = @Index(name = "ix_ppr_product", columnList = "product_id, effective_from"))
public class ProductPriceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "min_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductPriceRule() {}

    public ProductPriceRule(Product product, BigDecimal minPrice, BigDecimal maxPrice, LocalDate effectiveFrom) {
        if (minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must not exceed maxPrice");
        }
        this.product = product;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.effectiveFrom = effectiveFrom;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public BigDecimal getMinPrice() { return minPrice; }
    public BigDecimal getMaxPrice() { return maxPrice; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public Instant getCreatedAt() { return createdAt; }

    /** History is kept: a replaced rule is only closed, never edited or deleted. */
    public void closeOn(LocalDate lastDay) { this.effectiveTo = lastDay; }
}
