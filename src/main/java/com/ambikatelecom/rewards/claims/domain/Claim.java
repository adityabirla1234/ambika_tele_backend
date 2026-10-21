package com.ambikatelecom.rewards.claims.domain;

import com.ambikatelecom.rewards.customers.domain.Customer;
import com.ambikatelecom.rewards.products.domain.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "claims",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_claims_reference", columnNames = "claim_reference"),
                @UniqueConstraint(name = "uk_claims_device_key", columnNames = "device_key")
        },
        indexes = {
                @Index(name = "ix_claims_status_submitted", columnList = "status, submitted_at"),
                @Index(name = "ix_claims_customer", columnList = "customer_id, submitted_at"),
                @Index(name = "ix_claims_invoice", columnList = "invoice_number")
        })
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "claim_reference", nullable = false, length = 16, updatable = false)
    private String claimReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Keyed HMAC-SHA-256 of the normalized IMEI/serial. One device, one reward, for life. */
    @Column(name = "device_key", nullable = false, length = 64)
    private String deviceKey;

    @Column(name = "device_key_version", nullable = false)
    private int deviceKeyVersion;

    /** AES-GCM encrypted raw identifier, so staff can compare it with the bill. */
    @Column(name = "device_identifier_enc", nullable = false, length = 255)
    private String deviceIdentifierEnc;

    @Column(name = "invoice_number", length = 40)
    private String invoiceNumber;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "purchase_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.PENDING_REVIEW;

    // Snapshots: later rule edits never change this claim's eligibility or reward range.
    @Column(name = "rule_min_price", precision = 12, scale = 2)
    private BigDecimal ruleMinPrice;
    @Column(name = "rule_max_price", precision = 12, scale = 2)
    private BigDecimal ruleMaxPrice;
    @Column(name = "tier_id", nullable = false)
    private Long tierId;
    @Column(name = "tier_purchase_min", nullable = false, precision = 12, scale = 2)
    private BigDecimal tierPurchaseMin;
    @Column(name = "tier_purchase_max", nullable = false, precision = 12, scale = 2)
    private BigDecimal tierPurchaseMax;
    @Column(name = "reward_min", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMin;
    @Column(name = "reward_max", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardMax;

    @Column(name = "consent_accepted_at", nullable = false)
    private Instant consentAcceptedAt;
    @Column(name = "consent_version", nullable = false, length = 20)
    private String consentVersion;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /** Set when the retention routine or a staff erase request removed the personal data of this claim. */
    @Column(name = "personal_data_erased_at")
    private Instant personalDataErasedAt;

    /** Stored in place of the encrypted device number once it has been erased. */
    public static final String ERASED_MARKER = "ERASED";

    protected Claim() {}

    public Claim(String claimReference, Customer customer, Product product, String deviceKey, int deviceKeyVersion,
                 String deviceIdentifierEnc, String invoiceNumber, LocalDate purchaseDate, BigDecimal purchasePrice,
                 BigDecimal ruleMinPrice, BigDecimal ruleMaxPrice, Long tierId, BigDecimal tierPurchaseMin,
                 BigDecimal tierPurchaseMax, BigDecimal rewardMin, BigDecimal rewardMax, String consentVersion) {
        this.claimReference = claimReference;
        this.customer = customer;
        this.product = product;
        this.deviceKey = deviceKey;
        this.deviceKeyVersion = deviceKeyVersion;
        this.deviceIdentifierEnc = deviceIdentifierEnc;
        this.invoiceNumber = invoiceNumber;
        this.purchaseDate = purchaseDate;
        this.purchasePrice = purchasePrice;
        this.ruleMinPrice = ruleMinPrice;
        this.ruleMaxPrice = ruleMaxPrice;
        this.tierId = tierId;
        this.tierPurchaseMin = tierPurchaseMin;
        this.tierPurchaseMax = tierPurchaseMax;
        this.rewardMin = rewardMin;
        this.rewardMax = rewardMax;
        this.consentVersion = consentVersion;
        this.consentAcceptedAt = Instant.now();
    }

    @PrePersist
    void onCreate() { submittedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getClaimReference() { return claimReference; }
    public ClaimStatus getStatus() { return status; }
    public BigDecimal getRewardMin() { return rewardMin; }
    public BigDecimal getRewardMax() { return rewardMax; }
    public Customer getCustomer() { return customer; }
    public Product getProduct() { return product; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getDeviceIdentifierEnc() { return deviceIdentifierEnc; }
    public String getDeviceKey() { return deviceKey; }
    public int getDeviceKeyVersion() { return deviceKeyVersion; }
    public Instant getPersonalDataErasedAt() { return personalDataErasedAt; }
    public boolean isPersonalDataErased() { return personalDataErasedAt != null; }

    /** Key rotation only: same device, new key version and/or new encryption key. No status check by design. */
    public void rekey(String newKey, int newKeyVersion, String newIdentifierEnc) {
        this.deviceKey = newKey;
        this.deviceKeyVersion = newKeyVersion;
        this.deviceIdentifierEnc = newIdentifierEnc;
    }

    /**
     * Removes the personal data held on the claim itself: the encrypted device number and the invoice number.
     * replacementDeviceKey is null to keep the keyed device key (so the one-reward-per-device rule still holds), or a
     * random unique value to release the device.
     */
    public void erasePersonalData(String replacementDeviceKey) {
        this.deviceIdentifierEnc = ERASED_MARKER;
        this.invoiceNumber = null;
        if (replacementDeviceKey != null) this.deviceKey = replacementDeviceKey;
        this.personalDataErasedAt = Instant.now();
    }

    /** Marks a device key as released for a second-hand sale. Such a key is random, so it never matches a real device. */
    public static final String RELEASED_PREFIX = "RELEASED-";

    /**
     * Second-hand resale: the shop bought the device back and is selling it on, so the next buyer may claim a reward.
     * The old claim, its reward and its encrypted device number all stay. Only the key that blocks a new claim
     * for the same device is replaced with a random one.
     */
    public void releaseDevice(String randomHex, int currentKeyVersion) {
        this.deviceKey = RELEASED_PREFIX + randomHex;
        this.deviceKeyVersion = currentKeyVersion;
    }

    /** Staff fix for a mistyped IMEI or serial. Not allowed once approved: a reward then exists for the old device. */
    public void correctDevice(String newKey, int newKeyVersion, String newIdentifierEnc) {
        if (status == ClaimStatus.APPROVED) throw new IllegalStateException("Approved claims cannot be corrected");
        this.deviceKey = newKey;
        this.deviceKeyVersion = newKeyVersion;
        this.deviceIdentifierEnc = newIdentifierEnc;
    }
    public BigDecimal getTierPurchaseMin() { return tierPurchaseMin; }
    public BigDecimal getTierPurchaseMax() { return tierPurchaseMax; }

    // Allowed transitions: PENDING_REVIEW -> APPROVED | REJECTED, and REJECTED -> PENDING_REVIEW (reopen).
    public void markApproved() { requireStatus(ClaimStatus.PENDING_REVIEW); status = ClaimStatus.APPROVED; }
    public void markRejected() { requireStatus(ClaimStatus.PENDING_REVIEW); status = ClaimStatus.REJECTED; }
    public void markReopened() { requireStatus(ClaimStatus.REJECTED); status = ClaimStatus.PENDING_REVIEW; }

    private void requireStatus(ClaimStatus expected) {
        if (status != expected) throw new IllegalStateException("Claim is " + status + ", expected " + expected);
    }
}
