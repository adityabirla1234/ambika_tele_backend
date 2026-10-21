package com.ambikatelecom.rewards.claims.api;

import com.ambikatelecom.rewards.claims.application.AdminClaimQueryService;
import com.ambikatelecom.rewards.claims.application.AdminClaimViews.ClaimPage;
import com.ambikatelecom.rewards.claims.application.AdminClaimViews.Detail;
import com.ambikatelecom.rewards.claims.application.ClaimReviewService;
import com.ambikatelecom.rewards.privacy.ClaimErasureService;
import com.ambikatelecom.rewards.web.ApiException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** CLAIM_ADMIN only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/admin/claims")
public class AdminClaimController {

    public record ReasonRequest(@NotBlank(message = "Enter a reason.")
                                @Size(max = 500, message = "Keep the reason under 500 characters.") String reason) {}

    public record CorrectDeviceRequest(
            @NotBlank(message = "Enter the correct number from the bill.")
            @Size(max = 40, message = "That number is too long.") String deviceIdentifier,
            @NotBlank(message = "Enter a reason.")
            @Size(max = 500, message = "Keep the reason under 500 characters.") String reason) {}

    public record MarkPaidRequest(@NotNull(message = "Enter the amount paid.")
                                  @DecimalMin(value = "1", message = "Enter the amount paid.")
                                  @Digits(integer = 7, fraction = 2, message = "Enter a valid amount.") BigDecimal amount) {}

    /** The search text (a name, phone or invoice number) travels in the body, never in the URL, so it stays out of access logs and browser history. */
    public record SearchRequest(@Size(max = 20, message = "Unknown status filter.") String status,
                                @Size(max = 60, message = "Search text is too long.") String q,
                                @Min(value = 0, message = "Page must be 0 or more.") Integer page) {}

    private final AdminClaimQueryService queries;
    private final ClaimReviewService reviews;
    private final ClaimErasureService erasure;

    public AdminClaimController(AdminClaimQueryService queries, ClaimReviewService reviews, ClaimErasureService erasure) {
        this.queries = queries;
        this.reviews = reviews;
        this.erasure = erasure;
    }

    @PostMapping("/search")
    public ClaimPage search(@Valid @RequestBody SearchRequest body) {
        return queries.list(body.status(), body.q(), body.page() == null ? 0 : body.page());
    }

    /** Removes this claim's personal data now (customer request). See ClaimErasureService for exactly what goes. */
    @PostMapping("/{id}/erase-personal-data")
    public Detail erasePersonalData(@PathVariable Long id, Authentication auth) {
        if (erasure.erase(id, auth.getName(), "staff") == ClaimErasureService.Outcome.NOT_ELIGIBLE) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ERASABLE",
                    "Mark the reward as paid before erasing this claim's personal data.");
        }
        return queries.detail(id);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id) {
        return queries.detail(id);
    }

    @PostMapping("/{id}/approve")
    public Detail approve(@PathVariable Long id, Authentication auth) {
        reviews.approve(id, auth.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/reject")
    public Detail reject(@PathVariable Long id, @Valid @RequestBody ReasonRequest body, Authentication auth) {
        reviews.reject(id, body.reason(), auth.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/reopen")
    public Detail reopen(@PathVariable Long id, @Valid @RequestBody ReasonRequest body, Authentication auth) {
        reviews.reopen(id, body.reason(), auth.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/correct-device")
    public Detail correctDevice(@PathVariable Long id, @Valid @RequestBody CorrectDeviceRequest body, Authentication auth) {
        reviews.correctDevice(id, body.deviceIdentifier(), body.reason(), auth.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/mark-paid")
    public Detail markPaid(@PathVariable Long id, @Valid @RequestBody MarkPaidRequest body, Authentication auth) {
        reviews.markPaid(id, body.amount(), auth.getName());
        return queries.detail(id);
    }
}
