package com.ambikatelecom.rewards.claims.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTOs for the claim-admin screens. Entities are never returned from the API. */
public final class AdminClaimViews {

    private AdminClaimViews() {}

    public record Summary(Long id, String claimReference, String status, String customerName, String productName,
                          BigDecimal purchasePrice, String invoiceNumber, Instant submittedAt,
                          BigDecimal rewardMin, BigDecimal rewardMax) {}

    public record ReviewView(String decision, String reason, String reviewer, Instant at) {}

    public record RewardView(String state, BigDecimal rewardMin, BigDecimal rewardMax, BigDecimal paidAmount,
                             String paidBy, Instant paidAt) {}

    public record Detail(Long id, String claimReference, String status, String customerName, String customerPhone,
                         String productName, String productCategory, BigDecimal purchasePrice,
                         LocalDate purchaseDate, String invoiceNumber, long otherClaimsWithSameInvoice,
                         String deviceLabel, String deviceIdentifier, BigDecimal rewardMin, BigDecimal rewardMax,
                         Instant submittedAt, RewardView reward, List<ReviewView> history,
                         boolean personalDataErased) {}

    public record ClaimPage(List<Summary> items, int page, int totalPages, long totalItems) {}
}
