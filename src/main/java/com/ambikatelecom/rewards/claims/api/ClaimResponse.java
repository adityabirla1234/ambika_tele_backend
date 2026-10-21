package com.ambikatelecom.rewards.claims.api;

import java.math.BigDecimal;

/** statusToken lets this browser open the status page without retyping the IMEI or serial number. */
public record ClaimResponse(String claimReference, String status, BigDecimal rewardMin, BigDecimal rewardMax,
                            String statusToken) {}
