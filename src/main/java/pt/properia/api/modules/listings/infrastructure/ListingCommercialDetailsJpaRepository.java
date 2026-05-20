package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingCommercialDetails;

import java.util.Optional;
import java.util.UUID;

public interface ListingCommercialDetailsJpaRepository extends JpaRepository<ListingCommercialDetails, UUID> {

    Optional<ListingCommercialDetails> findByListingId(UUID listingId);
}
