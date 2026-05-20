package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingMedia;

import java.util.List;
import java.util.UUID;

public interface ListingMediaJpaRepository extends JpaRepository<ListingMedia, UUID> {

    List<ListingMedia> findByListingIdOrderBySortOrderAsc(UUID listingId);
}
