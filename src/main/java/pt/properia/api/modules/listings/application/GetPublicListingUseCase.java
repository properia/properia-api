package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.application.dto.PublicListingDetailDto;
import pt.properia.api.shared.domain.DomainException;

@Service
public class GetPublicListingUseCase {

    private final ListingRepository repository;

    public GetPublicListingUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Query(String publicId) {}

    public PublicListingDetailDto execute(Query query) {
        return repository.findPublishedByPublicId(query.publicId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));
    }
}
