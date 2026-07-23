package pt.properia.api.modules.auth.application;

import pt.properia.api.modules.auth.application.dto.AuthUserSummaryDto;
import pt.properia.api.modules.auth.application.dto.SessionUserDto;
import pt.properia.api.modules.auth.domain.AuthActionToken;
import pt.properia.api.modules.auth.domain.UserAuthIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthRepository {

    Optional<UserAuthIdentity> findLocalIdentityByEmail(String email);

    /** Advertisers a que este utilizador tem acesso efetivo (membership + advertiser ativo). */
    List<UUID> findAccessibleAdvertiserIds(UUID userId);

    Optional<UserAuthIdentity> findIdentityByProvider(String provider, String providerUserId);

    Optional<AuthUserSummaryDto> findUserByEmail(String email);

    Optional<AuthUserSummaryDto> findUserById(UUID id);

    AuthUserSummaryDto createLocalUser(CreateLocalUserInput input);

    AuthUserSummaryDto resolveOAuthIdentity(ResolveOAuthIdentityInput input);

    void touchIdentityLastLogin(UUID identityId);

    SessionUserDto buildSessionUser(UUID userId);

    void createActionToken(CreateActionTokenInput input);

    void invalidateActionTokens(UUID userId, String purpose);

    Optional<AuthActionToken> consumeActionToken(String tokenHash, String purpose);

    void updatePassword(UUID userId, String passwordHash, String algorithm);

    void markEmailVerified(UUID userId, String email);

    record CreateLocalUserInput(
        String name,
        String email,
        String passwordHash,
        boolean marketingConsent,
        boolean acceptTerms
    ) {}

    record ResolveOAuthIdentityInput(
        String provider,
        String providerUserId,
        String email,
        String name,
        String avatarUrl,
        boolean emailVerified
    ) {}

    record CreateActionTokenInput(
        UUID userId,
        String email,
        String purpose,
        String tokenHash,
        Instant expiresAt
    ) {}
}
