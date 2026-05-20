package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.shared.domain.DomainException;

import java.util.UUID;

@Service
public class ArchiveListingUseCase {

    private final ListingRepository repository;

    public ArchiveListingUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Command(UUID listingId, UUID advertiserId) {}

    public void execute(Command cmd) {
        repository.findByIdAndAdvertiserId(cmd.listingId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));
        repository.archive(cmd.listingId(), cmd.advertiserId());
    }
}
