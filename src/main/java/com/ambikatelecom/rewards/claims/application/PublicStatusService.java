package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.audit.application.AuditService;
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
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing status and scratch card. Reading status never writes anything. The scratch card never
 * generates an amount: it returns the range stored when the claim was approved and records the first reveal.
 */
@Service
public class PublicStatusService {

    static final String NOT_REGISTERED = "Device not registered. Please check the number, or try with another IMEI number.";

    private final ClaimRepository claims;
    private final ClaimReviewRepository reviews;
    private final RewardRepository rewards;
    private final DeviceKeyService devices;
    private final StatusTokenService tokens;
    private final AuditService audit;

    public PublicStatusService(ClaimRepository claims, ClaimReviewRepository reviews, RewardRepository rewards,
                               DeviceKeyService devices, StatusTokenService tokens, AuditService audit) {
        this.claims = claims;
        this.reviews = reviews;
        this.rewards = rewards;
        this.devices = devices;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ClaimStatusView lookup(String deviceIdentifier) {
        List<String> keys = devices.lookupKeys(deviceIdentifier);
        if (keys.isEmpty()) throw notRegistered();
        Claim claim = claims.findByDeviceKeyIn(keys).stream().findFirst().orElseThrow(PublicStatusService::notRegistered);
        return view(claim).withToken(tokens.issue(claim.getId()));
    }

    @Transactional(readOnly = true)
    public ClaimStatusView status(String reference, String token) {
        return view(authorize(reference, token));
    }

    @Transactional
    public ClaimStatusView scratch(String reference, String token) {
        Claim checked = authorize(reference, token);
        // Same lock order as the admin actions (claim row first), so a reveal and a mark-paid cannot deadlock.
        Claim claim = claims.findByIdForUpdate(checked.getId()).orElseThrow(PublicStatusService::invalidToken);
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_APPROVED", "Your claim has not been approved yet.");
        }
        Reward reward = rewards.findByClaimId(claim.getId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "NO_REWARD", "Your reward is not ready yet. Please try again shortly."));
        boolean first = reward.getState() == RewardState.ASSIGNED;
        reward.markRevealed();
        if (first) audit.record("REWARD_REVEALED", "CLAIM", claim.getId(), "customer", null);
        return view(claim);
    }

    private Claim authorize(String reference, String token) {
        Long claimId = tokens.verify(token);
        if (claimId == null || reference == null) throw invalidToken();
        Claim claim = claims.findById(claimId).orElseThrow(PublicStatusService::invalidToken);
        if (!claim.getClaimReference().equals(reference)) throw invalidToken();
        return claim;
    }

    private ClaimStatusView view(Claim claim) {
        String reason = null;
        if (claim.getStatus() == ClaimStatus.REJECTED) {
            reason = reviews.findFirstByClaimIdAndDecisionOrderByCreatedAtDescIdDesc(claim.getId(), ReviewDecision.REJECTED)
                    .map(ClaimReview::getReason).orElse(null);
        }
        Reward reward = claim.getStatus() == ClaimStatus.APPROVED ? rewards.findByClaimId(claim.getId()).orElse(null) : null;
        return new ClaimStatusView(claim.getClaimReference(), claim.getStatus().name(), reason,
                reward != null ? reward.getRewardMin() : claim.getRewardMin(),
                reward != null ? reward.getRewardMax() : claim.getRewardMax(),
                reward != null ? reward.getState().name() : null, null);
    }

    private static ApiException notRegistered() {
        return new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_REGISTERED", NOT_REGISTERED);
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "STATUS_TOKEN_INVALID",
                "Please enter your IMEI 1 or serial number again to see your claim.");
    }
}
