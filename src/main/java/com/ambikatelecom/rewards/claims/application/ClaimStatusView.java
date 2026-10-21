package com.ambikatelecom.rewards.claims.application;

import java.math.BigDecimal;

/**
 * Everything the public status page may see. No name, phone, invoice, price or device identifier.
 * rewardState is null until the claim is approved. statusToken is set only on a lookup response.
 */
public record ClaimStatusView(String claimReference, String status, String rejectionReason,
                              BigDecimal rewardMin, BigDecimal rewardMax, String rewardState, String statusToken) {

    public ClaimStatusView withToken(String token) {
        return new ClaimStatusView(claimReference, status, rejectionReason, rewardMin, rewardMax, rewardState, token);
    }
}
