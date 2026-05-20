package pt.properia.api.shared.infrastructure.web.jwt;

import java.util.UUID;

/**
 * Typed representation of the JWT payload.
 * Immutable record — passed through the security filter chain.
 */
public record JwtClaims(
    UUID userId,
    String email,
    String name,
    String role,
    String avatarUrl,
    boolean hasAdvertiserAccess,
    UUID activeAdvertiserId,
    UUID sessionId
) {}
