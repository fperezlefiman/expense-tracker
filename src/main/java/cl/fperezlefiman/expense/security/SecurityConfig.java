package cl.fperezlefiman.expense.security;

import cl.fperezlefiman.expense.ingest.PubSubTokenValidator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
        decoder.setJwtValidator(new PubSubTokenValidator(audience, serviceAccount));
        return decoder;
    }
}
