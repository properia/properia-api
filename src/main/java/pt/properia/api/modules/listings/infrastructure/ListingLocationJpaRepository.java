package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingLocation;

import java.util.Optional;
import java.util.UUID;

public interface ListingLocationJpaRepository extends JpaRepository<ListingLocation, UUID> {

    Optional<ListingLocation> findByListingId(UUID listingId);
}
