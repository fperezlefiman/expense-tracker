package cl.fperezlefiman.expense.ingest;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A valid Google token is not enough: it has to be issued for this endpoint's audience by the Pub/Sub
 * push account (ADR 0005).
 */
public class PubSubTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private final String audience;
    private final String serviceAccount;

    public PubSubTokenValidator(String audience, String serviceAccount) {
        this.audience = audience;
        this.serviceAccount = serviceAccount;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!GOOGLE_ISSUER.equals(jwt.getClaimAsString("iss"))) {
            return reject("wrong issuer");
        }
        List<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(audience)) {
            return reject("wrong audience");
        }
        if (!serviceAccount.equals(jwt.getClaimAsString("email"))) {
            return reject("wrong service account");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult reject(String reason) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", reason, null));
    }
}
