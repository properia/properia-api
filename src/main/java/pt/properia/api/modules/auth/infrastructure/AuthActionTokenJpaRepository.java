package pt.properia.api.modules.auth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.properia.api.modules.auth.domain.AuthActionToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AuthActionTokenJpaRepository extends JpaRepository<AuthActionToken, UUID> {

    @Query("SELECT t FROM AuthActionToken t WHERE t.tokenHash = :hash AND t.purpose = :purpose AND t.consumedAt IS NULL AND t.expiresAt > :now")
    Optional<AuthActionToken> findValidToken(@Param("hash") String hash,
                                              @Param("purpose") String purpose,
                                              @Param("now") Instant now);

    @Modifying
    @Query("UPDATE AuthActionToken t SET t.consumedAt = :now WHERE t.userId = :userId AND t.purpose = :purpose AND t.consumedAt IS NULL")
    void invalidateByUserAndPurpose(@Param("userId") UUID userId,
                                    @Param("purpose") String purpose,
                                    @Param("now") Instant now);
}
