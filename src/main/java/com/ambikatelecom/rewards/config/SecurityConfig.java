package com.ambikatelecom.rewards.config;

import com.ambikatelecom.rewards.security.StaffAuthService;
import com.ambikatelecom.rewards.security.StaffTokenFilter;
import com.ambikatelecom.rewards.web.SecurityErrorHandlers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Public routes are open. /api/admin/** needs CLAIM_ADMIN and /api/product-manager/** needs PRODUCT_MANAGER, both
 * from our own database (no role grants the other area). /api/staff/auth/login is open so staff can sign in; the other
 * /api/staff/auth/** routes need any valid session. The monthly report trigger is the one other open route and needs
 * the service secret. Everything else is denied.
 *
 * Staff authentication: StaffTokenFilter reads "Authorization: Bearer <session token>" and looks the session up in our
 * database. There are no cookies and no server-side HTTP session, so there is nothing for a forged cross-site request
 * to ride on: CSRF protection and an Origin check are not needed, and CORS does not allow credentials.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, StaffAuthService staffAuth, ObjectMapper mapper) throws Exception {
        SecurityErrorHandlers errors = new SecurityErrorHandlers(mapper);
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/staff/auth/login").permitAll()
                .requestMatchers("/api/staff/auth/**").authenticated()
                .requestMatchers("/api/staff/devices/**").hasAnyRole("CLAIM_ADMIN", "PRODUCT_MANAGER")
                .requestMatchers("/api/admin/**").hasRole("CLAIM_ADMIN")
                .requestMatchers("/api/product-manager/**").hasRole("PRODUCT_MANAGER")
                .anyRequest().denyAll())
            .headers(h -> h
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // The API is only reached over HTTPS behind Render's proxy, so send HSTS on every response.
                .httpStrictTransportSecurity(s -> s.requestMatcher(AnyRequestMatcher.INSTANCE)
                        .maxAgeInSeconds(31_536_000).includeSubDomains(true))
                // This service returns JSON only: nothing it sends should ever be rendered, framed or scripted.
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .cacheControl(Customizer.withDefaults()))
            .addFilterBefore(new StaffTokenFilter(staffAuth, mapper), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Staff sign-in is done by StaffAuthService, not by Spring's built-in user store. Declaring this bean stops Spring
     * Boot from creating its default "user" account with a random password (and printing it in the log).
     */
    @Bean
    UserDetailsService noBuiltInUsers() {
        return username -> {
            throw new UsernameNotFoundException("Staff sign-in is handled by StaffAuthService");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                                                    Environment env) {
        boolean dev = env.acceptsProfiles(Profiles.of("dev"));
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        // Fail at startup rather than run with a wildcard or an insecure origin.
        if (origins.isEmpty()) throw new IllegalStateException("CORS_ALLOWED_ORIGINS must list at least one origin.");
        for (String o : origins) {
            if (o.contains("*")) throw new IllegalStateException("CORS_ALLOWED_ORIGINS must not contain wildcards.");
            if (!dev && !o.startsWith("https://")) {
                throw new IllegalStateException("CORS_ALLOWED_ORIGINS must be https:// origins outside the dev profile.");
            }
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowCredentials(false); // no cookies are used: the staff token is sent in the Authorization header
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key", "X-Claim-Token",
                "X-Correlation-Id"));
        cfg.setExposedHeaders(List.of("X-Correlation-Id"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
