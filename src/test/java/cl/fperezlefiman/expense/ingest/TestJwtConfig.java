package cl.fperezlefiman.expense.ingest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces Google's JWKS decoder in tests: no network, but the same validator runs, so a token with a
 * wrong audience must still be rejected.
 */
@TestConfiguration
public class TestJwtConfig {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(@Value("${app.pubsub.audience}") String audience,
                              @Value("${app.pubsub.service-account}") String serviceAccount) {
        PubSubTokenValidator validator = new PubSubTokenValidator(audience, serviceAccount);

        return token -> {
            if (!token.startsWith("good") && !token.equals("foreign-audience")) {
                throw new BadJwtException("unrecognised test token");
            }
            Map<String, Object> claims = new HashMap<>();
            claims.put("iss", "https://accounts.google.com");
            claims.put("email", serviceAccount);
            claims.put("aud", List.of(token.equals("foreign-audience") ? "some-other-service" : audience));

            Jwt jwt = Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .claims(c -> c.putAll(claims))
                    .build();

            var result = validator.validate(jwt);
            if (result.hasErrors()) {
                throw new BadJwtException(result.getErrors().toString());
            }
            return jwt;
        };
    }
}
