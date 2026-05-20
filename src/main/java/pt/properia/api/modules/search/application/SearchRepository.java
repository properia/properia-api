package pt.properia.api.modules.search.application;

import pt.properia.api.modules.search.application.dto.SearchParams;
import pt.properia.api.modules.search.application.dto.SearchResultDto;

public interface SearchRepository {

    SearchResultDto search(SearchParams params);

    long count(SearchParams params);
}
