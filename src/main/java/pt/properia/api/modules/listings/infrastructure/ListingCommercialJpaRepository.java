package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingCommercial;

import java.util.Optional;
import java.util.UUID;

public interface ListingCommercialJpaRepository extends JpaRepository<ListingCommercial, UUID> {

    Optional<ListingCommercial> findByListingId(UUID listingId);
}
