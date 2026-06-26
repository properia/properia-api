package pt.properia.api.modules.crm.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.crm.domain.Visit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitJpaRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByAdvertiserIdOrderByStartsAtDesc(UUID advertiserId);

    List<Visit> findByBuyerUserIdOrderByStartsAtDesc(UUID buyerUserId);

    Optional<Visit> findByIdAndAdvertiserId(UUID id, UUID advertiserId);

    Optional<Visit> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    List<Visit> findByListingIdAndBuyerUserId(UUID listingId, UUID buyerUserId);

    List<Visit> findByAdvertiserIdAndStatusInAndStartsAtBetween(
        UUID advertiserId, Collection<String> statuses, Instant from, Instant to);
}
