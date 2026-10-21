package com.ambikatelecom.rewards.security;

import com.ambikatelecom.rewards.web.ApiError;
import com.ambikatelecom.rewards.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns "Authorization: Bearer <token>" into a logged-in staff user. Roles are read from the database on every
 * request, so removing a role or switching a user off takes effect at once.
 *
 * The token travels in a header that our own frontend code adds. A browser never attaches it by itself the way it
 * attaches a cookie, so a forged request from another website carries no login. That is why this scheme needs no CSRF
 * token and no Origin check.
 *
 * Not a Spring bean on purpose: SecurityConfig adds it to the security chain only. A bean would also be registered
 * as a plain servlet filter and run twice.
 */
public class StaffTokenFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/staff/auth/login";
    private static final String AUTH_PREFIX = "/api/staff/auth/";

    private final StaffAuthService auth;
    private final ObjectMapper mapper;

    public StaffTokenFilter(StaffAuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<StaffAuthService.SessionPrincipal> found = auth.authenticate(token);
            if (found.isPresent()) {
                StaffAuthService.SessionPrincipal who = found.get();
                // A bootstrap password may only be used to choose a new one.
                if (who.mustChangePassword() && !request.getRequestURI().startsWith(AUTH_PREFIX)) {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(response.getOutputStream(), ApiError.of("PASSWORD_CHANGE_REQUIRED",
                            "Please choose a new password before continuing.", MDC.get(CorrelationIdFilter.MDC_KEY)));
                    return;
                }
                List<GrantedAuthority> authorities = who.roles().stream()
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
                var authentication = new UsernamePasswordAuthenticationToken(who.email(), null, authorities);
                authentication.setDetails(who);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // An invalid, expired or revoked token simply leaves the request unauthenticated: protected routes answer
            // 401 and public routes carry on. A database error is NOT caught here, so an outage is a 500 and the
            // browser does not mistake it for "you were signed out".
        }
        chain.doFilter(request, response);
    }

    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
