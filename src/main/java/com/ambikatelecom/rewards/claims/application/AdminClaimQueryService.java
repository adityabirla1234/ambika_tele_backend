package com.ambikatelecom.rewards.claims.application;

import com.ambikatelecom.rewards.claims.application.AdminClaimViews.*;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.claims.domain.ClaimStatus;
import com.ambikatelecom.rewards.claims.persistence.ClaimRepository;
import com.ambikatelecom.rewards.claims.persistence.ClaimReviewRepository;
import com.ambikatelecom.rewards.common.EncryptionService;
import com.ambikatelecom.rewards.rewards.persistence.RewardRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminClaimQueryService {

    private static final int PAGE_SIZE = 20;

    private final ClaimRepository claims;
    private final ClaimReviewRepository reviews;
    private final RewardRepository rewards;
    private final EncryptionService encryption;
    private final com.ambikatelecom.rewards.audit.persistence.AuditLogRepository auditLogs;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public AdminClaimQueryService(ClaimRepository claims, ClaimReviewRepository reviews, RewardRepository rewards,
                                  EncryptionService encryption,
                                  com.ambikatelecom.rewards.audit.persistence.AuditLogRepository auditLogs,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.auditLogs = auditLogs;
        this.mapper = mapper;
        this.claims = claims;
        this.reviews = reviews;
        this.rewards = rewards;
        this.encryption = encryption;
    }

    public ClaimPage list(String status, String query, int page) {
        Set<ClaimStatus> statuses = parseStatuses(status);
        // Oldest pending first so nothing waits forever; everything else newest first.
        boolean pendingOnly = statuses.size() == 1 && statuses.contains(ClaimStatus.PENDING_REVIEW);
        Sort sort = Sort.by(pendingOnly ? Sort.Direction.ASC : Sort.Direction.DESC, "submittedAt");
        String term = query == null ? "" : query.toLowerCase().replaceAll("[%_\\\\]", "").trim();
        Page<Claim> result = claims.search(statuses, "%" + term + "%", PageRequest.of(Math.max(page, 0), PAGE_SIZE, sort));
        List<Summary> items = result.getContent().stream().map(c -> new Summary(c.getId(), c.getClaimReference(),
                c.getStatus().name(), c.getCustomer().getName(), productName(c), c.getPurchasePrice(),
                c.getInvoiceNumber(), c.getSubmittedAt(), c.getRewardMin(), c.getRewardMax())).toList();
        return new ClaimPage(items, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    public Detail detail(Long id) {
        Claim c = claims.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CLAIM_NOT_FOUND", "Claim not found."));
        boolean mobile = "mobile".equalsIgnoreCase(c.getProduct().getCategory());
        long sameInvoice = c.getInvoiceNumber() == null ? 0
                : claims.countByInvoiceNumberAndIdNot(c.getInvoiceNumber(), c.getId());
        RewardView reward = rewards.findByClaimId(id).map(r -> new RewardView(r.getState().name(), r.getRewardMin(),
                r.getRewardMax(), r.getPaidAmount(), r.getPaidBy(), r.getPaidAt())).orElse(null);
        List<ReviewView> history = new java.util.ArrayList<>(reviews.findByClaimIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(r -> new ReviewView(r.getDecision().name(), r.getReason(), r.getReviewer(), r.getCreatedAt()))
                .toList());
        // Device-number corrections live in the audit log (reason only), shown here in time order.
        auditLogs.findByEntityTypeAndEntityIdAndActionOrderByCreatedAtAscIdAsc("CLAIM", id, "CLAIM_DEVICE_CORRECTED")
                .forEach(a -> history.add(new ReviewView("DEVICE_CORRECTED", reasonOf(a.getMetadata()),
                        a.getActor(), a.getCreatedAt())));
        history.sort(java.util.Comparator.comparing(ReviewView::at));
        return new Detail(c.getId(), c.getClaimReference(), c.getStatus().name(), c.getCustomer().getName(),
                c.getCustomer().getPhoneE164(), productName(c), c.getProduct().getCategory(), c.getPurchasePrice(),
                c.getPurchaseDate(), c.getInvoiceNumber(), sameInvoice, mobile ? "IMEI 1" : "Serial number",
                c.isPersonalDataErased() ? "Erased" : encryption.decrypt(c.getDeviceIdentifierEnc()), c.getRewardMin(), c.getRewardMax(),
                c.getSubmittedAt(), reward, history, c.isPersonalDataErased());
    }

    private String reasonOf(String metadata) {
        try {
            return metadata == null ? null : mapper.readTree(metadata).path("reason").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String productName(Claim c) {
        return c.getProduct().getBrand() + " " + c.getProduct().getModel();
    }

    private static Set<ClaimStatus> parseStatuses(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return EnumSet.allOf(ClaimStatus.class);
        }
        try {
            return EnumSet.of(ClaimStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Unknown status filter.", "status");
        }
    }
}
