package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingFeatures;

import java.util.Optional;
import java.util.UUID;

public interface ListingFeaturesJpaRepository extends JpaRepository<ListingFeatures, UUID> {

    Optional<ListingFeatures> findByListingId(UUID listingId);
}
