package pt.properia.api.shared.infrastructure.web.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT generation and validation service using nimbus-jose-jwt.
 *
 * Token claims are intentionally identical to those produced by the Next.js backend
 * so tokens are interoperable during the Strangler Fig migration period.
 *
 * Claims layout (matches auth.ts SessionUser contract):
 *   sub             — userId (UUID)
 *   email           — user email
 *   name            — display name
 *   role            — AuthRole string
 *   avatarUrl       — profile picture (nullable)
 *   hasAdvertiserAccess — boolean
 *   activeAdvertiserId  — UUID string (nullable)
 *   sessionId       — UUID
 *   iss             — "properia"
 *   aud             — "properia-web"
 *   iat, exp        — standard timestamps
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        this.props = props;
    }

    public String generateToken(JwtClaims claims) {
        try {
            byte[] secret = props.getSecret().getBytes(StandardCharsets.UTF_8);
            JWSSigner signer = new MACSigner(secret);

            Instant now = Instant.now();
            Instant exp = now.plusSeconds(props.getTtlSeconds());

            var claimsSet = new JWTClaimsSet.Builder()
                .subject(claims.userId().toString())
                .issuer(props.getIssuer())
                .audience(props.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("email", claims.email())
                .claim("name", claims.name())
                .claim("role", claims.role())
                .claim("avatarUrl", claims.avatarUrl())
                .claim("hasAdvertiserAccess", claims.hasAdvertiserAccess())
                .claim("activeAdvertiserId",
                    claims.activeAdvertiserId() != null ? claims.activeAdvertiserId().toString() : null)
                .claim("sessionId", claims.sessionId().toString())
                .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJWT.sign(signer);
            return signedJWT.serialize();

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    public Optional<JwtClaims> validateToken(String token) {
        try {
            byte[] secret = props.getSecret().getBytes(StandardCharsets.UTF_8);
            JWSVerifier verifier = new MACVerifier(secret);

            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(verifier)) {
                log.debug("JWT signature invalid");
                return Optional.empty();
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            if (claims.getExpirationTime().before(new Date())) {
                log.debug("JWT expired for sub={}", claims.getSubject());
                return Optional.empty();
            }

            if (!props.getIssuer().equals(claims.getIssuer())) {
                log.debug("JWT issuer mismatch");
                return Optional.empty();
            }

            String advertiserIdStr = claims.getStringClaim("activeAdvertiserId");

            return Optional.of(new JwtClaims(
                UUID.fromString(claims.getSubject()),
                claims.getStringClaim("email"),
                claims.getStringClaim("name"),
                claims.getStringClaim("role"),
                claims.getStringClaim("avatarUrl"),
                Boolean.TRUE.equals(claims.getBooleanClaim("hasAdvertiserAccess")),
                advertiserIdStr != null ? UUID.fromString(advertiserIdStr) : null,
                UUID.fromString(claims.getStringClaim("sessionId"))
            ));

        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
