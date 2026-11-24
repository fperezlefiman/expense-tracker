package cl.fperezlefiman.expense.security;

import cl.fperezlefiman.expense.ingest.PubSubTokenValidator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> {
                }));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.pubsub.audience}") String audience,
                          @Value("${app.pubsub.service-account}") String serviceAccount) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).build();
        decoder.setJwtValidator(pubSubValidator(audience, serviceAccount));
        return decoder;
    }

    /**
     * {@code setJwtValidator} replaces the decoder's validators instead of adding to them, so the
     * defaults (expiry, not-before) have to be chained back in explicitly. Without this line an expired
     * token is accepted for as long as its signature verifies.
     */
    public static OAuth2TokenValidator<Jwt> pubSubValidator(String audience, String serviceAccount) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new PubSubTokenValidator(audience, serviceAccount));
    }
}
