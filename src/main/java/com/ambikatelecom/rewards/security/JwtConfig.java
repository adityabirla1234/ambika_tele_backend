package com.ambikatelecom.rewards.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Validates the Cloudflare Access JWT: signature (team JWKS), issuer, audience and expiry. Not used in dev. */
@Configuration
@Profile("!dev")
public class JwtConfig {

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.auth.cloudflare-team-domain}") String teamDomain,
                          @Value("${app.auth.cloudflare-aud}") String audience) {
        if (teamDomain.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("Set CF_ACCESS_TEAM_DOMAIN and CF_ACCESS_AUD to protect staff APIs.");
        }
        String issuer = teamDomain.endsWith("/") ? teamDomain.substring(0, teamDomain.length() - 1) : teamDomain;
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/cdn-cgi/access/certs").build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }
}
