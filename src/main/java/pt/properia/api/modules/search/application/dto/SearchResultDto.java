package pt.properia.api.modules.search.application.dto;

import java.util.List;

public record SearchResultDto(
    List<ListingSearchItemDto> items,
    long total,
    int page,
    int pageSize,
    int totalPages
) {}
