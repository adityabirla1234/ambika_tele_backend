package com.ambikatelecom.rewards.rewards.api;

import com.ambikatelecom.rewards.rewards.application.TierManagementService;
import com.ambikatelecom.rewards.rewards.application.TierManagementService.Input;
import com.ambikatelecom.rewards.rewards.application.TierManagementService.TierView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** PRODUCT_MANAGER only (enforced in SecurityConfig). Edits affect new claims only. */
@RestController
@RequestMapping("/api/product-manager/reward-tiers")
public class TierManagerController {

    public record TierRequest(
            @NotNull(message = "Enter the lowest purchase price.") BigDecimal purchaseMin,
            @NotNull(message = "Enter the highest purchase price.") BigDecimal purchaseMax,
            @NotNull(message = "Enter the lowest reward.") BigDecimal rewardMin,
            @NotNull(message = "Enter the highest reward.") BigDecimal rewardMax) {
        Input toInput() {
            return new Input(purchaseMin, purchaseMax, rewardMin, rewardMax);
        }
    }

    public record StatusRequest(@NotNull(message = "Choose active or inactive.") Boolean active) {}

    private final TierManagementService service;

    public TierManagerController(TierManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<TierView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TierView create(@Valid @RequestBody TierRequest body, Authentication auth) {
        return service.create(body.toInput(), auth.getName());
    }

    @PutMapping("/{id}")
    public TierView update(@PathVariable Long id, @Valid @RequestBody TierRequest body, Authentication auth) {
        return service.update(id, body.toInput(), auth.getName());
    }

    @PatchMapping("/{id}/status")
    public TierView status(@PathVariable Long id, @Valid @RequestBody StatusRequest body, Authentication auth) {
        return service.setActive(id, body.active(), auth.getName());
    }
}
