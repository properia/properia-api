package pt.properia.api.modules.listings.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.listings.domain.Listing;

import java.util.Optional;
import java.util.UUID;

public interface ListingJpaRepository extends JpaRepository<Listing, UUID> {

    Optional<Listing> findByPublicId(String publicId);

    Optional<Listing> findByIdAndAdvertiserId(UUID id, UUID advertiserId);
}
