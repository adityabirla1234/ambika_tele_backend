package com.ambikatelecom.rewards.claims.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ClaimRequest(
        @NotNull(message = "Choose your product.") Long productId,
        @NotNull(message = "Enter the purchase price.") BigDecimal purchasePrice,
        @NotNull(message = "Choose the purchase date.") LocalDate purchaseDate,
        @Size(max = 40, message = "Invoice number is too long.") String invoiceNumber,
        @NotBlank(message = "Enter the IMEI or serial number.") @Size(max = 40, message = "That number is too long.") String deviceIdentifier,
        @NotBlank(message = "Enter your name.") @Size(min = 2, max = 80, message = "Name must be 2 to 80 characters.") String customerName,
        @NotBlank(message = "Enter your mobile number.") @Size(max = 20, message = "That number is too long.") String phone,
        @AssertTrue(message = "Please accept to continue.") boolean consent) {}
