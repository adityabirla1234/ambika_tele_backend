package com.ambikatelecom.rewards.security;

import com.ambikatelecom.rewards.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff sign-in, sign-out and password change. Sign-in is the only open route here; the rest need a valid session
 * (SecurityConfig). Passwords and tokens are never logged: the request records are not printed anywhere.
 */
@RestController
@RequestMapping("/api/staff/auth")
public class StaffAuthController {

    public record LoginRequest(
            @NotBlank(message = "Enter your email.") @Size(max = 80, message = "That email is too long.") String email,
            @NotBlank(message = "Enter your password.") @Size(max = 200, message = "That password is too long.") String password) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password.") @Size(max = 200, message = "That password is too long.") String currentPassword,
            @NotBlank(message = "Enter a new password.") @Size(max = 200, message = "That password is too long.") String newPassword) {}

    public record LoginResponse(String token, Instant expiresAt, String email, List<String> roles,
                                boolean mustChangePassword) {}

    private final StaffAuthService service;

    public StaffAuthController(StaffAuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        StaffAuthService.LoginResult r = service.login(body.email(), body.password());
        return new LoginResponse(r.token(), r.expiresAt(), r.profile().email(), r.profile().roles(),
                r.profile().mustChangePassword());
    }

    @GetMapping("/me")
    public StaffAuthService.Profile me(Authentication auth) {
        return StaffAuthService.profileOf(principal(auth));
    }

    @PostMapping("/logout")
    public Map<String, String> logout(Authentication auth, HttpServletRequest request) {
        service.logout(StaffTokenFilter.bearerToken(request), auth.getName());
        return Map.of("status", "signed_out");
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest body, Authentication auth) {
        service.changePassword(principal(auth), body.currentPassword(), body.newPassword());
        return Map.of("status", "password_changed");
    }

    private static StaffAuthService.SessionPrincipal principal(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof StaffAuthService.SessionPrincipal p) return p;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again.");
    }
}
