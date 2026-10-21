package com.ambikatelecom.rewards.claims.api;

import com.ambikatelecom.rewards.claims.application.ClaimService;
import com.ambikatelecom.rewards.claims.application.StatusTokenService;
import com.ambikatelecom.rewards.claims.application.SubmitClaimCommand;
import com.ambikatelecom.rewards.claims.domain.Claim;
import com.ambikatelecom.rewards.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicClaimController {

    private final ClaimService claimService;
    private final StatusTokenService tokens;

    public PublicClaimController(ClaimService claimService, StatusTokenService tokens) {
        this.claimService = claimService;
        this.tokens = tokens;
    }

    @PostMapping("/claims")
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody ClaimRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9-]{16,64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Something went wrong. Please reload the page and try again.");
        }
        Claim claim = claimService.submit(new SubmitClaimCommand(req.productId(), req.purchasePrice(),
                req.purchaseDate(), req.invoiceNumber(), req.deviceIdentifier(), req.customerName(), req.phone()),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ClaimResponse(claim.getClaimReference(),
                claim.getStatus().name(), claim.getRewardMin(), claim.getRewardMax(), tokens.issue(claim.getId())));
    }
}
