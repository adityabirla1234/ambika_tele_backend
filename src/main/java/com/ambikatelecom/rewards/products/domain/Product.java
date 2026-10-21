package com.ambikatelecom.rewards.products.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One row per model/SKU, never per physical device. */
@Entity
@Table(name = "products", uniqueConstraints = @UniqueConstraint(name = "uk_products_sku", columnNames = "sku"))
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false, length = 80)
    private String brand;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false, length = 80)
    private String sku;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {}

    public Product(String category, String brand, String model, String sku) {
        this.category = category;
        this.brand = brand;
        this.model = model;
        this.sku = sku;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCategory() { return category; }
    public String getBrand() { return brand; }
    public String getModel() { return model; }
    public String getSku() { return sku; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return active; }

    public void update(String category, String brand, String model, String sku) {
        this.category = category;
        this.brand = brand;
        this.model = model;
        this.sku = sku;
    }
    public void setActive(boolean active) { this.active = active; }
}
