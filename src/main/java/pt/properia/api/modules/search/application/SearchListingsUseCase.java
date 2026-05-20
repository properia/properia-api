package pt.properia.api.modules.search.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.search.application.dto.SearchParams;
import pt.properia.api.modules.search.application.dto.SearchResultDto;

@Service
public class SearchListingsUseCase {

    private final SearchRepository repository;

    public SearchListingsUseCase(SearchRepository repository) {
        this.repository = repository;
    }

    public SearchResultDto search(SearchParams params) {
        return repository.search(params);
    }

    public long count(SearchParams params) {
        return repository.count(params);
    }
}
