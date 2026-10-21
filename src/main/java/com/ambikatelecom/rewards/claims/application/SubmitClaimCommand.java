package com.ambikatelecom.rewards.claims.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SubmitClaimCommand(Long productId, BigDecimal purchasePrice, LocalDate purchaseDate,
                                 String invoiceNumber, String deviceIdentifier, String customerName,
                                 String phone) {}
