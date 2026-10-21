package com.ambikatelecom.rewards.products.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTOs for the product manager screens. Entities are never returned from the API. */
public final class ProductViews {

    private ProductViews() {}

    /** minPrice and maxPrice are the price rule in force today, or null if the product has none. */
    public record Summary(Long id, String category, String brand, String model, String sku, boolean active,
                          BigDecimal minPrice, BigDecimal maxPrice, Instant updatedAt) {}

    /** state is CURRENT, SCHEDULED, PAST or REPLACED (superseded before it ever applied). */
    public record RuleView(Long id, BigDecimal minPrice, BigDecimal maxPrice, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String state) {}

    public record AuditView(String action, String actor, String changes, Instant at) {}

    public record Detail(Summary product, List<RuleView> rules, List<AuditView> history) {}

    public record ProductPage(List<Summary> items, int page, int totalPages, long totalItems) {}

    public record Filters(List<String> categories, List<String> brands) {}
}
