package pt.properia.api.modules.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.auth.application.AuthRepository;
import pt.properia.api.modules.auth.application.dto.AuthUserSummaryDto;
import pt.properia.api.modules.auth.application.dto.SessionUserDto;
import pt.properia.api.modules.auth.domain.AppUser;
import pt.properia.api.modules.auth.domain.AuthActionToken;
import pt.properia.api.modules.auth.domain.UserAuthIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaAuthRepository implements AuthRepository {

    private final AppUserJpaRepository users;
    private final AuthIdentityJpaRepository identities;
    private final AuthActionTokenJpaRepository actionTokens;
    private final AdvertiserAccessQuery advertiserAccess;
    private final ObjectMapper json;

    public JpaAuthRepository(AppUserJpaRepository users,
                              AuthIdentityJpaRepository identities,
                              AuthActionTokenJpaRepository actionTokens,
                              AdvertiserAccessQuery advertiserAccess,
                              ObjectMapper json) {
        this.users = users;
        this.identities = identities;
        this.actionTokens = actionTokens;
        this.advertiserAccess = advertiserAccess;
        this.json = json;
    }

    @Override
    public Optional<UserAuthIdentity> findLocalIdentityByEmail(String email) {
        return identities.findLocalByEmail(email);
    }

    @Override
    public Optional<UserAuthIdentity> findIdentityByProvider(String provider, String providerUserId) {
        return identities.findByProviderAndProviderUserId(provider, providerUserId);
    }

    @Override
    public Optional<AuthUserSummaryDto> findUserByEmail(String email) {
        return users.findByEmailIgnoreCase(email).map(this::toSummary);
    }

    @Override
    public Optional<AuthUserSummaryDto> findUserById(UUID id) {
        return users.findById(id).map(this::toSummary);
    }

    @Override
    public AuthUserSummaryDto createLocalUser(CreateLocalUserInput input) {
        String now = Instant.now().toString();
        String consents = buildConsentsJson(input.acceptTerms(), input.marketingConsent(), now);
        String prefs = buildPrefsJson(input.marketingConsent());

        var user = users.save(new AppUser(input.email(), input.name(), consents, prefs));

        identities.save(new UserAuthIdentity(
            user.getId(), "local", input.email(), input.email(),
            false, input.passwordHash(), "scrypt"
        ));

        return toSummary(user);
    }

    @Override
    public AuthUserSummaryDto resolveOAuthIdentity(ResolveOAuthIdentityInput input) {
        return identities.findByProviderAndProviderUserId(input.provider(), input.providerUserId())
            .map(identity -> toSummary(users.findById(identity.getUserId()).orElseThrow()))
            .orElseGet(() -> {
                String now = Instant.now().toString();
                // Login social: aceite dos termos é implícito ao autenticar via provider.
                String consents = buildConsentsJson(true, false, now);
                String prefs = buildPrefsJson(false);

                var user = users.save(new AppUser(input.email(), input.name(), consents, prefs));
                user.setAvatarUrl(input.avatarUrl());
                users.save(user);

                identities.save(new UserAuthIdentity(
                    user.getId(), input.provider(), input.providerUserId(),
                    input.email(), input.emailVerified(), null, null
                ));

                return toSummary(user);
            });
    }

    @Override
    public void touchIdentityLastLogin(UUID identityId) {
        identities.touchLastLogin(identityId, Instant.now());
    }

    @Override
    public List<UUID> findAccessibleAdvertiserIds(UUID userId) {
        return advertiserAccess.findAccessibleAdvertiserIds(userId);
    }

    @Override
    public SessionUserDto buildSessionUser(UUID userId) {
        var user = users.findById(userId).orElseThrow();
        List<UUID> advertiserIds = advertiserAccess.findAccessibleAdvertiserIds(userId);
        boolean hasAccess = !advertiserIds.isEmpty();
        UUID activeAdvertiserId = hasAccess ? advertiserIds.get(0) : null;

        return new SessionUserDto(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            user.getAvatarUrl(),
            hasAccess,
            activeAdvertiserId
        );
    }

    @Override
    public void createActionToken(CreateActionTokenInput input) {
        actionTokens.save(new AuthActionToken(
            input.userId(), input.email(), input.purpose(),
            input.tokenHash(), input.expiresAt()
        ));
    }

    @Override
    public void invalidateActionTokens(UUID userId, String purpose) {
        actionTokens.invalidateByUserAndPurpose(userId, purpose, Instant.now());
    }

    @Override
    public Optional<AuthActionToken> consumeActionToken(String tokenHash, String purpose) {
        return actionTokens.findValidToken(tokenHash, purpose, Instant.now())
            .map(token -> {
                token.consume();
                return actionTokens.save(token);
            });
    }

    @Override
    public void updatePassword(UUID userId, String passwordHash, String algorithm) {
        identities.updatePassword(userId, passwordHash, algorithm);
    }

    @Override
    public void markEmailVerified(UUID userId, String email) {
        users.setEmailVerified(userId, Instant.now());
        identities.markEmailVerified(userId, email);
    }

    private AuthUserSummaryDto toSummary(AppUser user) {
        return new AuthUserSummaryDto(user.getId(), user.getEmail(),
            user.getFullName(), user.getRole(), user.getAvatarUrl());
    }

    private String buildConsentsJson(boolean termsAccepted, boolean marketing, String now) {
        try {
            return json.writeValueAsString(Map.of(
                "termsPrivacy", Map.of("granted", termsAccepted, "version", "1.0", "updatedAt", now, "source", "register_local"),
                "marketing", Map.of("granted", marketing, "version", "1.0", "updatedAt", now, "source", "register_local")
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildPrefsJson(boolean marketing) {
        try {
            return json.writeValueAsString(Map.of(
                "marketingEnabled", marketing,
                "personalizationEnabled", false
            ));
        } catch (Exception e) {
            return "{}";
        }
    }
}
