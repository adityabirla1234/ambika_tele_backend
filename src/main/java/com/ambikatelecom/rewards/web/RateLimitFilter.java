package com.ambikatelecom.rewards.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sliding-window limits per client IP and per endpoint group. In memory, so it resets on restart and is
 * per instance; fine for a single Render instance.
 *
 * Phase 7 changes:
 * - The client IP no longer trusts the first X-Forwarded-For entry (the caller controls that one, so a
 *   attacker could rotate it and never hit a limit). We count app.rate-limit.trusted-proxy-hops entries from
 *   the RIGHT, which are the ones our own proxies appended. With 0 hops the socket address is used.
 * - Claim creation and status lookup also have a limit across ALL clients, a backstop against floods
 *   and identifier guessing from many addresses.
 * - Idle entries are purged on a timer, so the map cannot grow without bound.
 *
 * Groups: claim creation, report trigger, status lookup (the endpoint that could be used to guess identifiers), scratch,
 * and status polling. Polling needs a signed token, so it cannot be used to guess anything and gets a
 * generous limit (a phone polling every 5 seconds makes about 120 requests in 10 minutes, and many
 * customers share one mobile-carrier IP).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String name, String method, Pattern path, int limit, int globalLimit) {
        boolean matches(HttpServletRequest r) {
            return method.equals(r.getMethod()) && path.matcher(r.getRequestURI()).matches();
        }
    }

    private static final long PURGE_EVERY_MS = 60_000L;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final List<Rule> rules;
    private final long windowMs;
    private final int trustedHops;
    private final ObjectMapper mapper;
    private volatile long lastPurge = 0L;

    public RateLimitFilter(@Value("${app.rate-limit.claims-per-window}") int claimsLimit,
                           @Value("${app.rate-limit.lookups-per-window:15}") int lookupLimit,
                           @Value("${app.rate-limit.product-lookups-per-window:30}") int productLookupLimit,
                           @Value("${app.rate-limit.scratch-per-window:20}") int scratchLimit,
                           @Value("${app.rate-limit.status-per-window:600}") int statusLimit,
                           @Value("${app.rate-limit.login-per-window:10}") int loginLimit,
                           @Value("${app.rate-limit.claims-global-per-window:300}") int claimsGlobalLimit,
                           @Value("${app.rate-limit.lookups-global-per-window:300}") int lookupGlobalLimit,
                           @Value("${app.rate-limit.window-minutes}") int windowMinutes,
                           @Value("${app.rate-limit.trusted-proxy-hops:1}") int trustedHops,
                           ObjectMapper mapper) {
        this.windowMs = windowMinutes * 60_000L;
        this.trustedHops = Math.max(0, trustedHops);
        this.mapper = mapper;
        this.rules = List.of(
                new Rule("claims", "POST", Pattern.compile("/api/public/claims"), claimsLimit, claimsGlobalLimit),
                new Rule("lookup", "POST", Pattern.compile("/api/public/claims/status-lookup"), lookupLimit, lookupGlobalLimit),
                new Rule("productlookup", "POST", Pattern.compile("/api/public/products/lookup"), productLookupLimit, 0),
                new Rule("scratch", "POST", Pattern.compile("/api/public/claims/[^/]+/scratch"), scratchLimit, 0),
                new Rule("status", "GET", Pattern.compile("/api/public/claims/[^/]+/status"), statusLimit, 0),
                // Staff sign-in and password change: per IP only. A limit across all clients would let an attacker
                // lock the shop staff out just by flooding it; guessing one account is stopped by that account's lockout.
                new Rule("login", "POST", Pattern.compile("/api/staff/auth/login"), loginLimit, 0),
                new Rule("password", "POST", Pattern.compile("/api/staff/auth/change-password"), loginLimit, 0));
    }

    private Rule ruleFor(HttpServletRequest request) {
        for (Rule r : rules) if (r.matches(request)) return r;
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ruleFor(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Rule rule = ruleFor(request);
        long now = System.currentTimeMillis();
        purgeIdle(now);
        boolean allowed = tryAcquire(rule.name() + "|" + clientIp(request), rule.limit(), now);
        if (allowed && rule.globalLimit() > 0) {
            allowed = tryAcquire(rule.name() + "|*", rule.globalLimit(), now);
        }
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(windowMs / 1000));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of("RATE_LIMITED",
                "Too many attempts. Please wait a few minutes and try again.", MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private boolean tryAcquire(String key, int limit, long now) {
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Drops keys with no hit inside the window. Runs at most once a minute. */
    private void purgeIdle(long now) {
        if (now - lastPurge < PURGE_EVERY_MS) return;
        lastPurge = now;
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || now - last > windowMs;
            }
        });
    }

    /**
     * Address used as the limit key. X-Forwarded-For is "client, proxy1, proxy2": each proxy appends the address it
     * saw. Anything the caller put on the LEFT is untrusted, so we read from the right, skipping as many entries
     * as there are proxies in front of us minus one. Held in memory as a limit key only; never logged or stored.
     */
    private String clientIp(HttpServletRequest request) {
        if (trustedHops > 0) {
            String fwd = request.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                String[] parts = fwd.split(",");
                int idx = parts.length - trustedHops;
                if (idx >= 0) {
                    String ip = parts[idx].trim();
                    if (!ip.isEmpty() && ip.length() <= 45) return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
