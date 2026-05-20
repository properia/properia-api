package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingPricing;

import java.util.Optional;
import java.util.UUID;

public interface ListingPricingJpaRepository extends JpaRepository<ListingPricing, UUID> {

    Optional<ListingPricing> findByListingId(UUID listingId);
}
