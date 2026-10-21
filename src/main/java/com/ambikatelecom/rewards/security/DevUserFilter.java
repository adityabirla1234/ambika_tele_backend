package com.ambikatelecom.rewards.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DEV PROFILE ONLY. Lets you act as a staff user with an X-Dev-User header while running locally.
 * It is created by SecurityConfig only when the dev profile and app.auth.dev-header-enabled are both on.
 * Roles still come from the database.
 */
public class DevUserFilter extends OncePerRequestFilter {

    private final StaffAccessService access;

    public DevUserFilter(StaffAccessService access) {
        this.access = access;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String email = request.getHeader("X-Dev-User");
        if (email != null && !email.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            var auth = new UsernamePasswordAuthenticationToken(email.toLowerCase(), null, access.authoritiesFor(email));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
