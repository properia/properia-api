package pt.properia.api.modules.auth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.properia.api.modules.auth.domain.UserAuthIdentity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AuthIdentityJpaRepository extends JpaRepository<UserAuthIdentity, UUID> {

    @Query("SELECT i FROM UserAuthIdentity i WHERE LOWER(i.email) = LOWER(:email) AND i.provider = 'local'")
    Optional<UserAuthIdentity> findLocalByEmail(@Param("email") String email);

    Optional<UserAuthIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

    @Modifying
    @Query("UPDATE UserAuthIdentity i SET i.lastLoginAt = :ts WHERE i.id = :id")
    void touchLastLogin(@Param("id") UUID id, @Param("ts") Instant ts);

    @Modifying
    @Query("UPDATE UserAuthIdentity i SET i.passwordHash = :hash, i.passwordAlgorithm = :algo WHERE i.userId = :userId AND i.provider = 'local'")
    void updatePassword(@Param("userId") UUID userId, @Param("hash") String hash, @Param("algo") String algo);

    @Modifying
    @Query("UPDATE UserAuthIdentity i SET i.emailVerified = true WHERE i.userId = :userId AND LOWER(i.email) = LOWER(:email)")
    void markEmailVerified(@Param("userId") UUID userId, @Param("email") String email);
}
