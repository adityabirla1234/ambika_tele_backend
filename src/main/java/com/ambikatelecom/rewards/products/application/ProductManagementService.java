package com.ambikatelecom.rewards.products.application;

import com.ambikatelecom.rewards.products.application.ProductViews.AuditView;
import com.ambikatelecom.rewards.products.application.ProductViews.Detail;
import com.ambikatelecom.rewards.products.application.ProductViews.Filters;
import com.ambikatelecom.rewards.products.application.ProductViews.ProductPage;
import com.ambikatelecom.rewards.products.application.ProductViews.RuleView;
import com.ambikatelecom.rewards.products.application.ProductViews.Summary;
import com.ambikatelecom.rewards.products.domain.Product;
import com.ambikatelecom.rewards.products.domain.ProductPriceRule;
import com.ambikatelecom.rewards.products.persistence.ProductAuditLogRepository;
import com.ambikatelecom.rewards.products.persistence.ProductPriceRuleRepository;
import com.ambikatelecom.rewards.products.persistence.ProductRepository;
import com.ambikatelecom.rewards.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product manager operations. Products are deactivated, never deleted. Price rules are versioned: a change
 * closes the old rule and adds a new row, so history stays. Claims keep their own snapshots, so none of this
 * changes an existing claim.
 */
@Service
public class ProductManagementService {

    private static final int PAGE_SIZE = 20;
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(10_000_000);

    private final ProductRepository products;
    private final ProductPriceRuleRepository rules;
    private final ProductAuditLogRepository auditLogs;
    private final ProductAuditService audit;
    private final ZoneId shopZone;

    public ProductManagementService(ProductRepository products, ProductPriceRuleRepository rules,
                                    ProductAuditLogRepository auditLogs, ProductAuditService audit,
                                    @Value("${app.shop.timezone}") String shopTimezone) {
        this.products = products;
        this.rules = rules;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.shopZone = ZoneId.of(shopTimezone);
    }

    public record Input(String category, String brand, String model, String sku,
                        BigDecimal minPrice, BigDecimal maxPrice, LocalDate effectiveFrom) {}

    // ---- reads ----

    @Transactional(readOnly = true)
    public ProductPage list(String q, String category, String brand, String status, int page) {
        Collection<Boolean> actives = switch (status == null ? "ALL" : status) {
            case "ACTIVE" -> List.of(true);
            case "INACTIVE" -> List.of(false);
            default -> List.of(true, false);
        };
        Page<Product> result = products.search(actives, like(category, true), like(brand, true), like(q, false),
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("brand", "model")));
        LocalDate today = today();
        Map<Long, ProductPriceRule> current = currentRules(result.getContent().stream().map(Product::getId).toList(), today);
        List<Summary> items = result.getContent().stream().map(p -> summary(p, current.get(p.getId()))).toList();
        return new ProductPage(items, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Filters filters() {
        return new Filters(products.categories(), products.brands());
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        return buildDetail(load(id));
    }

    // ---- writes ----

    @Transactional
    public Detail create(Input in, String actor) {
        String category = text(in.category(), "category", "Enter the category.", 60);
        String brand = text(in.brand(), "brand", "Enter the brand.", 80);
        String model = text(in.model(), "model", "Enter the model.", 120);
        String sku = text(in.sku(), "sku", "Enter the SKU.", 80).toUpperCase();
        BigDecimal min = price(in.minPrice(), "minPrice", "Enter the lowest eligible price.");
        BigDecimal max = price(in.maxPrice(), "maxPrice", "Enter the highest eligible price.");
        requireOrdered(min, max);
        LocalDate from = effectiveFrom(in.effectiveFrom());
        if (products.findBySku(sku).isPresent()) throw skuExists();

        Product p = products.save(new Product(category, brand, model, sku));
        ProductPriceRule rule = addRule(p, min, max, from);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("category", category);
        changes.put("brand", brand);
        changes.put("model", model);
        changes.put("sku", sku);
        audit.record("PRODUCT", p.getId(), p.getId(), "PRODUCT_CREATED", actor, changes);
        audit.record("PRICE_RULE", rule.getId(), p.getId(), "PRICE_RULE_ADDED", actor, ruleChange(null, null, min, max, from));
        return buildDetail(p);
    }

    @Transactional
    public Detail update(Long id, Input in, String actor) {
        Product p = load(id);
        String category = text(in.category(), "category", "Enter the category.", 60);
        String brand = text(in.brand(), "brand", "Enter the brand.", 80);
        String model = text(in.model(), "model", "Enter the model.", 120);
        String sku = text(in.sku(), "sku", "Enter the SKU.", 80).toUpperCase();
        products.findBySku(sku).filter(other -> !other.getId().equals(id)).ifPresent(other -> { throw skuExists(); });

        Map<String, Object> diff = new LinkedHashMap<>();
        diff("category", p.getCategory(), category, diff);
        diff("brand", p.getBrand(), brand, diff);
        diff("model", p.getModel(), model, diff);
        diff("sku", p.getSku(), sku, diff);

        boolean priceGiven = in.minPrice() != null || in.maxPrice() != null;
        BigDecimal min = null;
        BigDecimal max = null;
        LocalDate from = null;
        if (priceGiven) {
            min = price(in.minPrice(), "minPrice", "Enter the lowest eligible price.");
            max = price(in.maxPrice(), "maxPrice", "Enter the highest eligible price.");
            requireOrdered(min, max);
            from = effectiveFrom(in.effectiveFrom());
        }

        if (!diff.isEmpty()) {
            p.update(category, brand, model, sku);
            audit.record("PRODUCT", id, id, "PRODUCT_UPDATED", actor, diff);
        }
        if (priceGiven) {
            ProductPriceRule current = currentRules(List.of(id), today()).get(id);
            boolean same = current != null && current.getMinPrice().compareTo(min) == 0
                    && current.getMaxPrice().compareTo(max) == 0 && !from.isAfter(today());
            if (!same) {
                ProductPriceRule rule = addRule(p, min, max, from);
                audit.record("PRICE_RULE", rule.getId(), id, "PRICE_RULE_CHANGED", actor, ruleChange(
                        current == null ? null : current.getMinPrice(), current == null ? null : current.getMaxPrice(),
                        min, max, from));
            }
        }
        return buildDetail(p);
    }

    @Transactional
    public Detail setActive(Long id, boolean active, String actor) {
        Product p = load(id);
        if (p.isActive() != active) {
            p.setActive(active);
            audit.record("PRODUCT", id, id, active ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", actor,
                    Map.of("active", active));
        }
        return buildDetail(p);
    }

    // ---- helpers ----

    /** Keeps one rule in force at a time: earlier rules end the day before, later-scheduled ones are voided. */
    private ProductPriceRule addRule(Product p, BigDecimal min, BigDecimal max, LocalDate from) {
        for (ProductPriceRule r : rules.findByProductIdOrderByEffectiveFromDescIdDesc(p.getId())) {
            if (!r.getEffectiveFrom().isBefore(from)) {
                r.closeOn(r.getEffectiveFrom().minusDays(1));
            } else if (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(from)) {
                r.closeOn(from.minusDays(1));
            }
        }
        return rules.save(new ProductPriceRule(p, min, max, from));
    }

    private Detail buildDetail(Product p) {
        LocalDate today = today();
        ProductPriceRule current = currentRules(List.of(p.getId()), today).get(p.getId());
        List<RuleView> ruleViews = rules.findByProductIdOrderByEffectiveFromDescIdDesc(p.getId()).stream()
                .map(r -> new RuleView(r.getId(), r.getMinPrice(), r.getMaxPrice(), r.getEffectiveFrom(),
                        r.getEffectiveTo(), ruleState(r, current, today))).toList();
        List<AuditView> history = auditLogs.findTop50ByProductIdOrderByCreatedAtDescIdDesc(p.getId()).stream()
                .map(a -> new AuditView(a.getAction(), a.getActor(), a.getChanges(), a.getCreatedAt())).toList();
        return new Detail(summary(p, current), ruleViews, history);
    }

    private static String ruleState(ProductPriceRule r, ProductPriceRule current, LocalDate today) {
        if (r.getEffectiveTo() != null && r.getEffectiveTo().isBefore(r.getEffectiveFrom())) return "REPLACED";
        if (current != null && current.getId().equals(r.getId())) return "CURRENT";
        if (r.getEffectiveFrom().isAfter(today)) return "SCHEDULED";
        return "PAST";
    }

    private Map<Long, ProductPriceRule> currentRules(List<Long> ids, LocalDate today) {
        if (ids.isEmpty()) return Map.of();
        // Ordered newest first, so keeping the first rule per product gives the one in force.
        return rules.findCurrentForProducts(ids, today).stream().collect(Collectors.toMap(
                r -> r.getProduct().getId(), r -> r, (first, later) -> first));
    }

    private static Summary summary(Product p, ProductPriceRule rule) {
        return new Summary(p.getId(), p.getCategory(), p.getBrand(), p.getModel(), p.getSku(), p.isActive(),
                rule == null ? null : rule.getMinPrice(), rule == null ? null : rule.getMaxPrice(), p.getUpdatedAt());
    }

    private Product load(Long id) {
        return products.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_FOUND", "Product not found."));
    }

    private LocalDate today() {
        return LocalDate.now(shopZone);
    }

    private LocalDate effectiveFrom(LocalDate requested) {
        LocalDate today = today();
        if (requested == null) return today;
        if (requested.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "The start date cannot be in the past.", "effectiveFrom");
        }
        return requested;
    }

    private static String text(String raw, String field, String message, int max) {
        String v = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (v.isEmpty() || v.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    v.isEmpty() ? message : "Keep this under " + max + " characters.", field);
        }
        return v;
    }

    private static BigDecimal price(BigDecimal v, String field, String message) {
        if (v == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, field);
        if (v.signum() <= 0 || v.stripTrailingZeros().scale() > 0 || v.compareTo(MAX_PRICE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Enter a whole number of rupees, more than zero.", field);
        }
        return v.setScale(2);
    }

    private static void requireOrdered(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "The lowest price cannot be higher than the highest price.", "minPrice");
        }
    }

    private static ApiException skuExists() {
        return new ApiException(HttpStatus.CONFLICT, "SKU_EXISTS", "Another product already uses this SKU.", "sku");
    }

    private static void diff(String field, Object before, Object after, Map<String, Object> out) {
        if (!before.equals(after)) out.put(field, Map.of("from", before, "to", after));
    }

    private static Map<String, Object> ruleChange(BigDecimal oldMin, BigDecimal oldMax, BigDecimal min, BigDecimal max,
                                                  LocalDate from) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (oldMin != null) {
            m.put("minPrice", Map.of("from", oldMin.toPlainString(), "to", min.toPlainString()));
            m.put("maxPrice", Map.of("from", oldMax.toPlainString(), "to", max.toPlainString()));
        } else {
            m.put("minPrice", min.toPlainString());
            m.put("maxPrice", max.toPlainString());
        }
        m.put("effectiveFrom", from.toString());
        return m;
    }

    /** "%" when no filter, otherwise a lower-case contains pattern (exact match for category and brand). */
    private static String like(String raw, boolean exact) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if (v.isEmpty()) return "%";
        return exact ? v : "%" + v + "%";
    }
}
