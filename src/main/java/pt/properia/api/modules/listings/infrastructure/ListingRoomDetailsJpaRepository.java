package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.ListingRoomDetails;

import java.util.Optional;
import java.util.UUID;

public interface ListingRoomDetailsJpaRepository extends JpaRepository<ListingRoomDetails, UUID> {

    Optional<ListingRoomDetails> findByListingId(UUID listingId);
}
