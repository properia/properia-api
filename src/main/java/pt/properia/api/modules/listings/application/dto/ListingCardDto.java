package pt.properia.api.modules.listings.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ListingCardDto(
    UUID id,
    String publicId,
    String status,
    String businessType,
    String propertyType,
    String title,
    BigDecimal priceAmount,
    String priceCurrency,
    int bedrooms,
    BigDecimal bathrooms,
    int suites,
    BigDecimal usableAreaM2,
    BigDecimal grossAreaM2,
    String city,
    String district,
    String parish,
    String neighborhood,
    String heroImageUrl,
    String energyRating,
    Instant publishedAt,
    Instant firstPublishedAt,
    Instant createdAt
) {}
