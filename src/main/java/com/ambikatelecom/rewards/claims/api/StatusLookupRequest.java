package com.ambikatelecom.rewards.claims.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StatusLookupRequest(
        @NotBlank(message = "Enter your IMEI 1 or serial number.")
        @Size(max = 40, message = "That number is too long.") String deviceIdentifier) {}
