package pt.properia.api.modules.buyers.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pt.properia.api.modules.buyers.domain.BuyerProfile;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BuyerProfileJpaRepository extends JpaRepository<BuyerProfile, UUID> {

    // eligibleIds: pré-filtro calculado em SQL puro (BuyerService.computeEligibleIds) para
    // critérios que o JPQL não expressa bem (jsonb, EXISTS a buyer_listing_matches) — null
    // = sem restrição. Aplicado ANTES da paginação, ao contrário de filtrar em memória depois
    // do Page já vir com LIMIT/OFFSET aplicado (o que desalinharia total/totalPages).
    @Query("SELECT b FROM BuyerProfile b WHERE b.advertiserId = :advertiserId " +
           "AND (:status IS NULL OR CAST(b.status AS string) = :status) " +
           "AND (:assignedTo IS NULL OR b.assignedToUserId = :assignedTo) " +
           "AND (:q IS NULL OR LOWER(b.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(b.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) " +
           "AND (:eligibleIds IS NULL OR b.id IN :eligibleIds)")
    Page<BuyerProfile> search(UUID advertiserId, String status, UUID assignedTo, String q, Set<UUID> eligibleIds, Pageable pageable);

    Optional<BuyerProfile> findByAdvertiserIdAndId(UUID advertiserId, UUID id);

    long countByAdvertiserIdAndStatus(UUID advertiserId, String status);

    Optional<BuyerProfile> findByConsentToken(UUID consentToken);

    List<BuyerProfile> findAllByAdvertiserIdAndStatus(UUID advertiserId, String status);
}
