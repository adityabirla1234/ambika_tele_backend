package com.ambikatelecom.rewards.privacy;

import com.ambikatelecom.rewards.audit.application.AuditService;
import com.ambikatelecom.rewards.audit.persistence.AuditLogRepository;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.domain.ClaimStatus;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.claims.persistence.ClaimReviewRepository;
import com.ambikatelecom.rewards.customers.domain.Customer;
import com.ambikatelecom.rewards.rewards.domain.RewardState;
import com.ambikatelecom.rewards.rewards.persistence.RewardRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes the personal data of one claim. Used by the retention routine and by the staff "erase" action.
 *
 * What goes: the encrypted IMEI/serial, the invoice number, the free-text reasons on the review history and on device
 * corrections, and (once the customer has no un-erased claim left) the customer's name and phone number.
 * What stays: the claim row itself, status, product, price, dates and reward amounts (the shop's sales and payout
 * record), and the audit trail without personal data. The keyed device key stays unless
 * app.retention.keep-device-key is false, because it is what enforces "one reward per device for life".
 *
 * An approved claim whose reward has not been paid is never erased: the customer may still need to be reached.
 */
@Service
public class ClaimErasureService {

    public enum Outcome { ERASED, ALREADY_ERASED, NOT_ELIGIBLE }

    private final ClaimRepository claims;
    private final ClaimReviewRepository reviews;
    private final AuditLogRepository auditLogs;
    private final AuditService audit;
    private final RewardRepository rewards;
    private final boolean keepDeviceKey;

    public ClaimErasureService(ClaimRepository claims, ClaimReviewRepository reviews, AuditLogRepository auditLogs,
                               AuditService audit, RewardRepository rewards,
                               @Value("${app.retention.keep-device-key:true}") boolean keepDeviceKey) {
        this.claims = claims;
        this.reviews = reviews;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.rewards = rewards;
        this.keepDeviceKey = keepDeviceKey;
    }

    /** via is a fixed word ("retention" or "staff"), written to the audit log. */
    @Transactional
    public Outcome erase(Long claimId, String actor, String via) {
        Claim claim = claims.findByIdForUpdate(claimId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CLAIM_NOT_FOUND", "Claim not found."));
        if (claim.isPersonalDataErased()) return Outcome.ALREADY_ERASED;
        if (claim.getStatus() == ClaimStatus.APPROVED) {
            boolean paid = rewards.findByClaimId(claimId).map(r -> r.getState() == RewardState.REDEEMED).orElse(false);
            if (!paid) return Outcome.NOT_ELIGIBLE;
        }
        claim.erasePersonalData(keepDeviceKey ? null : "ERASED-" + UUID.randomUUID().toString().replace("-", ""));
        claims.saveAndFlush(claim);
        reviews.scrubReasons(claimId);
        auditLogs.scrubDeviceCorrectionReasons(claimId);
        Customer customer = claim.getCustomer();
        if (claims.countByCustomerIdAndPersonalDataErasedAtIsNull(customer.getId()) == 0) {
            customer.anonymize();
        }
        audit.record("CLAIM_PERSONAL_DATA_ERASED", "CLAIM", claimId, actor, "{\"via\":\"" + via + "\"}");
        return Outcome.ERASED;
    }
}
