package com.ambikatelecom.rewards.products.api;

import com.ambikatelecom.rewards.products.application.ProductManagementService;
import com.ambikatelecom.rewards.products.application.ProductManagementService.Input;
import com.ambikatelecom.rewards.products.application.ProductViews.Detail;
import com.ambikatelecom.rewards.products.application.ProductViews.Filters;
import com.ambikatelecom.rewards.products.application.ProductViews.ProductPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** PRODUCT_MANAGER only (enforced in SecurityConfig). Products are deactivated, never deleted. */
@RestController
@RequestMapping("/api/product-manager/products")
public class ProductManagerController {

    /** On update, leave both prices out to keep the current rule. On create the service requires them. */
    public record ProductRequest(
            @NotBlank(message = "Enter the category.") @Size(max = 60, message = "Keep this under 60 characters.") String category,
            @NotBlank(message = "Enter the brand.") @Size(max = 80, message = "Keep this under 80 characters.") String brand,
            @NotBlank(message = "Enter the model.") @Size(max = 120, message = "Keep this under 120 characters.") String model,
            @NotBlank(message = "Enter the SKU.") @Size(max = 80, message = "Keep this under 80 characters.") String sku,
            BigDecimal minPrice, BigDecimal maxPrice, LocalDate effectiveFrom) {
        Input toInput() {
            return new Input(category, brand, model, sku, minPrice, maxPrice, effectiveFrom);
        }
    }

    public record StatusRequest(@NotNull(message = "Choose active or inactive.") Boolean active) {}

    private final ProductManagementService service;

    public ProductManagerController(ProductManagementService service) {
        this.service = service;
    }

    @GetMapping
    public ProductPage list(@RequestParam(required = false, defaultValue = "") String q,
                            @RequestParam(required = false, defaultValue = "") String category,
                            @RequestParam(required = false, defaultValue = "") String brand,
                            @RequestParam(required = false, defaultValue = "ALL") String status,
                            @RequestParam(defaultValue = "0") int page) {
        return service.list(q, category, brand, status, page);
    }

    @GetMapping("/filters")
    public Filters filters() {
        return service.filters();
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@Valid @RequestBody ProductRequest body, Authentication auth) {
        return service.create(body.toInput(), auth.getName());
    }

    @PutMapping("/{id}")
    public Detail update(@PathVariable Long id, @Valid @RequestBody ProductRequest body, Authentication auth) {
        return service.update(id, body.toInput(), auth.getName());
    }

    @PatchMapping("/{id}/status")
    public Detail status(@PathVariable Long id, @Valid @RequestBody StatusRequest body, Authentication auth) {
        return service.setActive(id, body.active(), auth.getName());
    }
}
