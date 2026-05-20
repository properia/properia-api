package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.application.dto.ListingCardDto;

import java.util.List;
import java.util.UUID;

@Service
public class GetAdvertiserListingsUseCase {

    private final ListingRepository repository;

    public GetAdvertiserListingsUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Query(UUID advertiserId) {}

    public List<ListingCardDto> execute(Query query) {
        return repository.findByAdvertiserId(query.advertiserId());
    }
}
