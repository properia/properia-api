package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PublishListingUseCase {

    private final ListingRepository repository;

    public PublishListingUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Command(UUID listingId, UUID advertiserId) {}

    public Listing execute(Command cmd) {
        var listing = repository.findByIdAndAdvertiserId(cmd.listingId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        if ("archived".equals(listing.getStatus())) {
            throw new DomainException("INVALID_STATUS", "Um anúncio arquivado não pode ser publicado.");
        }

        var now = Instant.now();
        listing.setStatus("published");
        listing.setPublishedAt(now);
        if (listing.getFirstPublishedAt() == null) {
            listing.setFirstPublishedAt(now);
        }

        return repository.save(listing);
    }
}
