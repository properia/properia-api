package pt.properia.api.modules.crm.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.crm.domain.Lead;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadJpaRepository extends JpaRepository<Lead, UUID> {

    List<Lead> findByAdvertiserIdOrderByCreatedAtDesc(UUID advertiserId);

    Optional<Lead> findByIdAndAdvertiserId(UUID id, UUID advertiserId);

    Optional<Lead> findFirstByListingIdAndUserIdOrderByCreatedAtAsc(UUID listingId, UUID userId);
}
