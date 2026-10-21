package com.ambikatelecom.rewards.web;

import java.time.Instant;
import java.util.List;

/** Stable error shape returned by every failing endpoint. */
public record ApiError(String code, String message, String correlationId, Instant timestamp,
                       List<FieldIssue> fields) {

    public record FieldIssue(String field, String message) {}

    public static ApiError of(String code, String message, String correlationId) {
        return new ApiError(code, message, correlationId, Instant.now(), List.of());
    }
}
