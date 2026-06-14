package pt.properia.api.modules.buyers.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pt.properia.api.modules.buyers.domain.BuyerProfile;

import java.util.Optional;
import java.util.UUID;

public interface BuyerProfileJpaRepository extends JpaRepository<BuyerProfile, UUID> {

    @Query("SELECT b FROM BuyerProfile b WHERE b.advertiserId = :advertiserId " +
           "AND (:status IS NULL OR CAST(b.status AS string) = :status) " +
           "AND (:assignedTo IS NULL OR b.assignedToUserId = :assignedTo) " +
           "AND (:q IS NULL OR LOWER(b.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(b.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    Page<BuyerProfile> search(UUID advertiserId, String status, UUID assignedTo, String q, Pageable pageable);

    Optional<BuyerProfile> findByAdvertiserIdAndId(UUID advertiserId, UUID id);

    long countByAdvertiserIdAndStatus(UUID advertiserId, String status);

    Optional<BuyerProfile> findByConsentToken(UUID consentToken);
}
