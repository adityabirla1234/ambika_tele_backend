package com.ambikatelecom.rewards.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for the staff APIs. The API keeps no session, but the staff browser holds a Cloudflare Access
 * cookie, and Cloudflare can turn that cookie into the Cf-Access-Jwt-Assertion header for requests that reach us.
 * That makes a forged cross-site POST look logged in. So every state-changing staff request (POST, PUT, PATCH,
 * DELETE) must come from one of our own frontend origins: the browser-set Origin header, or the Referer origin
 * when Origin is absent. A request with neither is refused. Browsers cannot be made to forge either header.
 * Public routes carry no ambient login, so they need no such check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OriginCheckFilter extends OncePerRequestFilter {

    private final Set<String> allowed;
    private final ObjectMapper mapper;

    public OriginCheckFilter(@Value("${app.cors.allowed-origins}") String allowedOrigins, ObjectMapper mapper) {
        this.allowed = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(OriginCheckFilter::stripSlash)
                .collect(Collectors.toUnmodifiableSet());
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean staff = path.startsWith("/api/admin/") || path.startsWith("/api/product-manager/");
        String m = request.getMethod();
        boolean safe = m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
        return !staff || safe;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || origin.equals("null")) {
            origin = originOf(request.getHeader("Referer"));
        }
        if (origin != null && allowed.contains(stripSlash(origin))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of("ORIGIN_NOT_ALLOWED",
                "This request did not come from the rewards website.", MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private static String originOf(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            URI u = URI.create(referer.trim());
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
