package pt.properia.api.modules.listings.application.dto;

import java.util.UUID;

public record ListingMediaDto(
    UUID id,
    String mediaType,
    String url,
    String thumbnailUrl,
    int sortOrder,
    boolean isCover,
    String caption,
    String roomHint
) {}
