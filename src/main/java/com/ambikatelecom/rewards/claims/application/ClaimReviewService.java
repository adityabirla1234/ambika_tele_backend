package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.common.EncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.domain.ClaimReview;
import com.ambikatelecom.rewards.claims.domain.ClaimStatus;
import com.ambikatelecom.rewards.claims.domain.ReviewDecision;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.claims.persistence.ClaimReviewRepository;
import com.ambikatelecom.rewards.rewards.domain.Reward;
import com.ambikatelecom.rewards.rewards.domain.RewardState;
import com.ambikatelecom.rewards.rewards.persistence.RewardRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every action locks the claim row first (SELECT ... FOR UPDATE), re-checks the current state inside the
 * transaction, and writes the review, reward and audit rows together. A failure rolls everything back.
 * Repeating an action that already took effect returns success without changing anything.
 */
@Service
public class ClaimReviewService {

    private final ClaimRepository claims;
    private final ClaimReviewRepository reviews;
    private final RewardRepository rewards;
    private final AuditService audit;
    private final DeviceKeyService devices;
    private final EncryptionService encryption;
    private final ObjectMapper mapper;

    public ClaimReviewService(ClaimRepository claims, ClaimReviewRepository reviews, RewardRepository rewards,
                              AuditService audit, DeviceKeyService devices, EncryptionService encryption,
                              ObjectMapper mapper) {
        this.claims = claims;
        this.reviews = reviews;
        this.rewards = rewards;
        this.audit = audit;
        this.devices = devices;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    @Transactional
    public void approve(Long claimId, String actor) {
        Claim claim = lock(claimId);
        if (claim.getStatus() == ClaimStatus.APPROVED) return; // retry or double click
        requireStatus(claim, ClaimStatus.PENDING_REVIEW, "Only a pending claim can be approved.");
        reviews.save(new ClaimReview(claim, ReviewDecision.APPROVED, null, actor));
        rewards.save(new Reward(claim)); // UNIQUE(claim_id) is the final safeguard
        claim.markApproved();
        audit.record("CLAIM_APPROVED", "CLAIM", claimId, actor, null);
    }

    @Transactional
    public void reject(Long claimId, String reason, String actor) {
        Claim claim = lock(claimId);
        if (claim.getStatus() == ClaimStatus.REJECTED) return;
        requireStatus(claim, ClaimStatus.PENDING_REVIEW, "Only a pending claim can be rejected.");
        reviews.save(new ClaimReview(claim, ReviewDecision.REJECTED, cleanReason(reason), actor));
        claim.markRejected();
        audit.record("CLAIM_REJECTED", "CLAIM", claimId, actor, null);
    }

    @Transactional
    public void reopen(Long claimId, String reason, String actor) {
        Claim claim = lock(claimId);
        if (claim.getStatus() == ClaimStatus.PENDING_REVIEW) return;
        requireStatus(claim, ClaimStatus.REJECTED, "Only a rejected claim can be reopened.");
        reviews.save(new ClaimReview(claim, ReviewDecision.REOPENED, cleanReason(reason), actor));
        claim.markReopened();
        audit.record("CLAIM_REOPENED", "CLAIM", claimId, actor, null);
    }

    @Transactional
    public void markPaid(Long claimId, BigDecimal amount, String actor) {
        Claim claim = lock(claimId);
        requireStatus(claim, ClaimStatus.APPROVED, "Only an approved claim can be marked as paid.");
        Reward reward = rewards.findByClaimId(claimId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "NO_REWARD", "This claim has no reward to pay."));
        if (reward.getState() == RewardState.REDEEMED) return; // already paid, keep the first record
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Enter the amount paid.", "amount");
        }
        reward.markPaid(amount, actor);
        audit.record("REWARD_PAID", "CLAIM", claimId, actor, "{\"amount\":" + amount.toPlainString() + "}");
    }

    /**
     * Fixes a mistyped IMEI or serial number on a pending or rejected claim, using the number printed on the bill.
     * Releases the old device key (so a real owner of that number is not blocked) and takes the new one.
     * The audit entry holds the reason only, never the old or new number.
     */
    @Transactional
    public void correctDevice(Long claimId, String rawIdentifier, String reason, String actor) {
        Claim claim = lock(claimId);
        if (claim.getStatus() == ClaimStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "The device number of an approved claim cannot be changed.");
        }
        if (claim.isPersonalDataErased()) {
            throw new ApiException(HttpStatus.CONFLICT, "PERSONAL_DATA_ERASED",
                    "This claim's personal data has been erased, so its device number cannot be changed.");
        }
        String why = cleanReason(reason);
        boolean imei = "mobile".equalsIgnoreCase(claim.getProduct().getCategory());
        DeviceKeyService.NormalizedDevice device = devices.normalize(imei, rawIdentifier);
        if (device.allKeys().contains(claim.getDeviceKey())) return; // same number: nothing to change
        if (claims.existsByDeviceKeyInAndIdNot(device.allKeys(), claimId)) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_ALREADY_REGISTERED",
                    "That number is already registered on another claim.", "deviceIdentifier");
        }
        claim.correctDevice(device.key(), device.keyVersion(), encryption.encrypt(device.normalized()));
        claims.saveAndFlush(claim); // a lost race on UNIQUE(device_key) fails here and rolls everything back
        audit.record("CLAIM_DEVICE_CORRECTED", "CLAIM", claimId, actor, json(Map.of("reason", why)));
    }

    private String json(Map<String, String> data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Claim lock(Long claimId) {
        return claims.findByIdForUpdate(claimId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CLAIM_NOT_FOUND", "Claim not found."));
    }

    private static void requireStatus(Claim claim, ClaimStatus expected, String message) {
        if (claim.getStatus() != expected) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    message + " This claim is " + claim.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }
    }

    private static String cleanReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Enter a reason.", "reason");
        }
        return r.length() > 500 ? r.substring(0, 500) : r;
    }
}
