package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.domain.ClaimStatus;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.rewards.domain.RewardState;
import com.ambikatelecom.rewards.rewards.persistence.RewardRepository;
import com.ambikatelecom.rewards.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second-hand resale. The shop buys a sold device back and sells it again, so the next buyer may claim a reward.
 * Normally "one reward per device for life" blocks that. Releasing swaps the old claim's device key for a random one.
 * The old claim, its reward and its history all stay. Staff only ever type the device number: it is looked up through
 * the keyed hash and is never shown, logged or put in the audit entry.
 */
@Service
public class DeviceReleaseService {

    /** What staff see after typing a device number. productName and sku come from the old claim. */
    public record DeviceStatus(boolean registered, String claimReference, String claimStatus, String productName,
                               String sku, LocalDate purchaseDate, String rewardState, boolean canRelease,
                               String blockedReason) {}

    private final ClaimRepository claims;
    private final RewardRepository rewards;
    private final DeviceKeyService devices;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public DeviceReleaseService(ClaimRepository claims, RewardRepository rewards, DeviceKeyService devices,
                                AuditService audit, ObjectMapper mapper) {
        this.claims = claims;
        this.rewards = rewards;
        this.devices = devices;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public DeviceStatus check(String raw) {
        List<Claim> found = claims.findByDeviceKeyIn(keysFor(raw));
        if (found.isEmpty()) return new DeviceStatus(false, null, null, null, null, null, null, false, null);
        return describe(found.get(0));
    }

    @Transactional
    public DeviceStatus release(String raw, boolean secondHand, String reason, String actor) {
        if (!secondHand) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Tick the box to confirm this is a second-hand sale.", "secondHand");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Enter a reason.", "reason");
        if (why.length() > 500) why = why.substring(0, 500);

        List<Claim> found = claims.findByDeviceKeyIn(keysFor(raw));
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_REGISTERED",
                    "No claim uses this device number, so there is nothing to release.");
        }
        Claim claim = claims.findByIdForUpdate(found.get(0).getId()).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND", "Claim not found."));
        String blocked = blockedReason(claim);
        if (blocked != null) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", blocked);

        claim.releaseDevice(UUID.randomUUID().toString().replace("-", ""), devices.currentVersion());
        claims.saveAndFlush(claim); // UNIQUE(device_key) still holds: the new key is random
        audit.record("DEVICE_RELEASED", "CLAIM", claim.getId(), actor, json(why));
        return new DeviceStatus(false, null, null, null, null, null, null, false, null);
    }

    private List<String> keysFor(String raw) {
        List<String> keys = devices.lookupKeys(raw);
        if (keys.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_IDENTIFIER",
                    "Enter the 15-digit IMEI 1 or the serial number exactly as printed on the product or bill.",
                    "deviceIdentifier");
        }
        return keys;
    }

    private DeviceStatus describe(Claim c) {
        String reward = rewards.findByClaimId(c.getId()).map(r -> r.getState().name()).orElse(null);
        String blocked = blockedReason(c);
        return new DeviceStatus(true, c.getClaimReference(), c.getStatus().name(),
                c.getProduct().getBrand() + " " + c.getProduct().getModel(), c.getProduct().getSku(),
                c.getPurchaseDate(), reward, blocked == null, blocked);
    }

    /** Null when the device can be released. The first customer's claim must be settled first, so nobody loses a reward. */
    private String blockedReason(Claim c) {
        if (c.getStatus() == ClaimStatus.PENDING_REVIEW) {
            return "The first claim for this device is still waiting for review. Approve or reject it first.";
        }
        if (c.getStatus() == ClaimStatus.APPROVED) {
            boolean paid = rewards.findByClaimId(c.getId()).map(r -> r.getState() == RewardState.REDEEMED).orElse(false);
            if (!paid) return "The reward for the first claim has not been paid yet. Mark it as paid first.";
        }
        return null;
    }

    private String json(String reason) {
        try {
            return mapper.writeValueAsString(Map.of("reason", reason));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
