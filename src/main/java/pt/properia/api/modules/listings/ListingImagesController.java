package pt.properia.api.modules.listings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.modules.listings.persistence.JpaListingImagesRepository;

@RestController
@RequestMapping("/public/listings/{publicId}")
@Tag(name = "Public Listings Images", description = "Listing images management")
@RequiredArgsConstructor
public class ListingImagesController {

  private final JpaListingImagesRepository imagesRepo;
  private final ListingJpaRepository listingRepo;

  @GetMapping("/images")
  @Operation(summary = "Get listing images", description = "Returns all images for a listing by public ID")
  public ResponseEntity<List<String>> getListingImages(@PathVariable String publicId) {
    var listing = listingRepo.findByPublicId(publicId);
    if (listing.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    var images = imagesRepo.findByListingIdOrderByPosition(listing.get().getId());
    return ResponseEntity.ok(images.stream().map(img -> img.getUrl()).toList());
  }
}
