package pt.properia.api.modules.auth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.auth.domain.AppUser;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AppUserJpaRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.emailVerifiedAt = :ts WHERE u.id = :id AND u.emailVerifiedAt IS NULL")
    int setEmailVerified(@Param("id") UUID id, @Param("ts") Instant ts);

    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.lastLoginAt = :ts WHERE u.id = :id")
    void touchLastLogin(@Param("id") UUID id, @Param("ts") Instant ts);
}
