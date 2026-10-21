package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.common.EncryptionService;
import com.ambikatelecom.rewards.customers.application.PhoneNormalizer;
import com.ambikatelecom.rewards.customers.domain.Customer;
import com.ambikatelecom.rewards.customers.persistence.CustomerRepository;
import com.ambikatelecom.rewards.idempotency.domain.IdempotencyRecord;
import com.ambikatelecom.rewards.idempotency.persistence.IdempotencyRecordRepository;
import com.ambikatelecom.rewards.products.domain.Product;
import com.ambikatelecom.rewards.products.domain.ProductPriceRule;
import com.ambikatelecom.rewards.products.persistence.ProductPriceRuleRepository;
import com.ambikatelecom.rewards.products.persistence.ProductRepository;
import com.ambikatelecom.rewards.rewards.domain.RewardTier;
import com.ambikatelecom.rewards.rewards.persistence.RewardTierRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One database transaction per submission. Slow network calls never happen here.
 * Races are settled by the unique constraints (device_key, phone, idempotency key); ClaimService retries once.
 */
@Service
public class ClaimTransaction {

    static final String SCOPE = "CLAIM_SUBMIT";
    private static final String CONSENT_VERSION = "v1";
    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ProductRepository products;
    private final ProductPriceRuleRepository rules;
    private final RewardTierRepository tiers;
    private final CustomerRepository customers;
    private final ClaimRepository claims;
    private final IdempotencyRecordRepository idempotency;
    private final DeviceKeyService deviceKeys;
    private final EncryptionService encryption;
    private final AuditService audit;
    private final ZoneId shopZone;
    private final SecureRandom random = new SecureRandom();

    public ClaimTransaction(ProductRepository products, ProductPriceRuleRepository rules, RewardTierRepository tiers,
                            CustomerRepository customers, ClaimRepository claims,
                            IdempotencyRecordRepository idempotency, DeviceKeyService deviceKeys,
                            EncryptionService encryption, AuditService audit,
                            @Value("${app.shop.timezone}") String shopTimezone) {
        this.products = products;
        this.rules = rules;
        this.tiers = tiers;
        this.customers = customers;
        this.claims = claims;
        this.idempotency = idempotency;
        this.deviceKeys = deviceKeys;
        this.encryption = encryption;
        this.audit = audit;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    @Transactional
    public Claim submit(SubmitClaimCommand cmd, String idempotencyKey) {
        LocalDate today = LocalDate.now(shopZone);

        Product product = products.findById(cmd.productId()).filter(Product::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_AVAILABLE",
                        "This product is no longer available. Please choose another.", "productId"));
        boolean imei = "mobile".equalsIgnoreCase(product.getCategory());

        // 1. Normalize and validate fields.
        String name = cmd.customerName().trim().replaceAll("\\s+", " ");
        String phone = PhoneNormalizer.toE164(cmd.phone());
        DeviceKeyService.NormalizedDevice device = deviceKeys.normalize(imei, cmd.deviceIdentifier());
        // A product's SKU is the IMEI or serial number of that exact device. This stops a customer from sending
        // someone else's product (and its price range) with their own device number.
        if (!product.getSku().replaceAll("[\\s-]", "").toUpperCase().equals(device.normalized())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_PRODUCT_MISMATCH",
                    "This number does not match the product found for it. Please check the number.", "deviceIdentifier");
        }
        String invoice = cmd.invoiceNumber() == null ? "" : cmd.invoiceNumber().trim().toUpperCase();
        if (imei && invoice.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Enter the invoice number from your bill.", "invoiceNumber");
        }
        if (cmd.purchaseDate().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Purchase date cannot be in the future.", "purchaseDate");
        }
        BigDecimal price = cmd.purchasePrice();
        if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Enter the price in whole rupees.", "purchasePrice");
        }
        price = price.setScale(2);

        // 2. Idempotency: same key and payload replays the original result; a different payload is a conflict.
        String fingerprint = sha256(String.join("|", String.valueOf(product.getId()), price.toPlainString(),
                cmd.purchaseDate().toString(), invoice, device.key(), phone, name));
        Optional<IdempotencyRecord> existing = idempotency.findByScopeAndIdempotencyKey(SCOPE, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This request key was already used for a different claim. Please start again.");
            }
            return claims.findById(existing.get().getResourceId()).orElseThrow();
        }

        // 3. Eligibility: product rule, then reward tier. Ineligible claims are never saved.
        Optional<ProductPriceRule> rule = rules.findCurrent(product.getId(), today, PageRequest.of(0, 1))
                .stream().findFirst();
        if (rule.isPresent() && (price.compareTo(rule.get().getMinPrice()) < 0
                || price.compareTo(rule.get().getMaxPrice()) > 0)) {
            throw ineligible(rule.get().getMinPrice(), rule.get().getMaxPrice());
        }
        RewardTier tier = tiers.findActiveForPrice(price, today).stream().findFirst()
                .orElseThrow(() -> {
                    List<RewardTier> all = tiers.findAllActive(today);
                    BigDecimal lo = all.stream().map(RewardTier::getPurchaseMin).min(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
                    BigDecimal hi = all.stream().map(RewardTier::getPurchaseMax).max(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
                    return ineligible(lo, hi);
                });

        // 4. One reward per device for life.
        if (claims.existsByDeviceKeyIn(device.allKeys())) { // every key version, so a half-finished rotation cannot hide a duplicate
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_ALREADY_REGISTERED",
                    "This device is already registered. Each device can claim a reward only once.");
        }

        // 5. Save customer (reuse by phone), claim and idempotency record together.
        Customer customer = customers.findByPhoneE164(phone).orElseGet(() -> customers.saveAndFlush(new Customer(name, phone)));
        Claim claim = new Claim(newReference(), customer, product, device.key(), device.keyVersion(),
                encryption.encrypt(device.normalized()), invoice.isEmpty() ? null : invoice, cmd.purchaseDate(), price,
                rule.map(ProductPriceRule::getMinPrice).orElse(null), rule.map(ProductPriceRule::getMaxPrice).orElse(null),
                tier.getId(), tier.getPurchaseMin(), tier.getPurchaseMax(), tier.getRewardMin(), tier.getRewardMax(),
                CONSENT_VERSION);
        claims.saveAndFlush(claim);
        idempotency.save(new IdempotencyRecord(SCOPE, idempotencyKey, fingerprint, claim.getId()));
        audit.record("CLAIM_SUBMITTED", "CLAIM", claim.getId(), "customer",
                "{\"productId\":" + product.getId() + ",\"tierId\":" + tier.getId() + "}");
        return claim;
    }

    private ApiException ineligible(BigDecimal min, BigDecimal max) {
        NumberFormat inr = NumberFormat.getIntegerInstance(new Locale("en", "IN"));
        String message = "Sorry, this purchase isn't eligible for a reward. Rewards are available on purchases between "
                + "\u20b9" + inr.format(min) + " and \u20b9" + inr.format(max)
                + ". Please check the price, or speak to our team at Ambika Telecom.";
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_NOT_ELIGIBLE", message);
    }

    private String newReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("AMB-");
            for (int i = 0; i < 8; i++) sb.append(REF_ALPHABET.charAt(random.nextInt(REF_ALPHABET.length())));
            String ref = sb.toString();
            if (!claims.existsByClaimReference(ref)) return ref;
        }
        throw new IllegalStateException("Could not generate a unique claim reference");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
