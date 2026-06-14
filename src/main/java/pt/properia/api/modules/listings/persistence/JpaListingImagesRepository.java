package pt.properia.api.modules.listings.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.listings.domain.ListingImage;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaListingImagesRepository extends JpaRepository<ListingImage, UUID> {
    List<ListingImage> findByListingIdOrderByPosition(UUID listingId);
}
