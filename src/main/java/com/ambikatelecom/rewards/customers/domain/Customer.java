package com.ambikatelecom.rewards.customers.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "customers",
        uniqueConstraints = @UniqueConstraint(name = "uk_customers_phone", columnNames = "phone_e164"),
        indexes = @Index(name = "ix_customers_created", columnList = "created_at, id"))
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "phone_e164", nullable = false, length = 16)
    private String phoneE164;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {}

    public Customer(String name, String phoneE164) {
        this.name = name;
        this.phoneE164 = phoneE164;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    /** Used by the retention routine. The placeholder keeps the unique phone column unique and shows no real number. */
    public void anonymize() {
        this.name = "Erased customer";
        this.phoneE164 = "ERASED-" + id;
    }

    public boolean isAnonymized() { return phoneE164.startsWith("ERASED-"); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getPhoneE164() { return phoneE164; }
    public Instant getCreatedAt() { return createdAt; }
}
