package com.ambikatelecom.rewards.claims.api;

import com.ambikatelecom.rewards.claims.application.DeviceReleaseService;
import com.ambikatelecom.rewards.claims.application.DeviceReleaseService.DeviceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CLAIM_ADMIN or PRODUCT_MANAGER (enforced in SecurityConfig). The device number travels in the body, never the URL. */
@RestController
@RequestMapping("/api/staff/devices")
public class DeviceReleaseController {

    public record CheckRequest(@NotBlank(message = "Enter the IMEI or serial number.")
                               @Size(max = 40, message = "That number is too long.") String deviceIdentifier) {}

    public record ReleaseRequest(
            @NotBlank(message = "Enter the IMEI or serial number.")
            @Size(max = 40, message = "That number is too long.") String deviceIdentifier,
            boolean secondHand,
            @NotBlank(message = "Enter a reason.")
            @Size(max = 500, message = "Keep the reason under 500 characters.") String reason) {}

    private final DeviceReleaseService service;

    public DeviceReleaseController(DeviceReleaseService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public DeviceStatus check(@Valid @RequestBody CheckRequest body) {
        return service.check(body.deviceIdentifier());
    }

    @PostMapping("/release")
    public DeviceStatus release(@Valid @RequestBody ReleaseRequest body, Authentication auth) {
        return service.release(body.deviceIdentifier(), body.secondHand(), body.reason(), auth.getName());
    }
}
