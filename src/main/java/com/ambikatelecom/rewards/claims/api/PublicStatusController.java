package com.ambikatelecom.rewards.claims.api;

import com.ambikatelecom.rewards.claims.application.ClaimStatusView;
import com.ambikatelecom.rewards.claims.application.PublicStatusService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {id} in these paths is the claim reference. Status and scratch also need the signed token from the
 * lookup (or submit) response, sent in X-Claim-Token, so a reference alone reveals nothing.
 */
@RestController
@RequestMapping("/api/public/claims")
public class PublicStatusController {

    static final String TOKEN_HEADER = "X-Claim-Token";

    private final PublicStatusService service;

    public PublicStatusController(PublicStatusService service) {
        this.service = service;
    }

    @PostMapping("/status-lookup")
    public ResponseEntity<ClaimStatusView> lookup(@Valid @RequestBody StatusLookupRequest req) {
        return noStore(service.lookup(req.deviceIdentifier()));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ClaimStatusView> status(@PathVariable("id") String id,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return noStore(service.status(id, token));
    }

    @PostMapping("/{id}/scratch")
    public ResponseEntity<ClaimStatusView> scratch(@PathVariable("id") String id,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return noStore(service.scratch(id, token));
    }

    private static ResponseEntity<ClaimStatusView> noStore(ClaimStatusView body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
