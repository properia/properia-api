package pt.properia.api.modules.listings.interfaces.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateListingRequest(
    @NotNull String businessType,
    @NotNull String propertyType,
    String propertySubtype,
    @NotBlank String title,
    String descriptionRaw,
    String descriptionShort,
    BigDecimal priceAmount,
    Integer bedrooms,
    BigDecimal bathrooms,
    Integer suites,
    Integer garageSpaces,
    Integer parkingSpaces,
    BigDecimal usableAreaM2,
    BigDecimal grossAreaM2,
    BigDecimal lotAreaM2,
    String city,
    String district,
    String municipality,
    String parish,
    String neighborhood,
    String street,
    String postalCode,
    Double latitude,
    Double longitude,
    String locationPrecision,
    String conditionDeclared,
    String furnishedDeclared,
    String energyRating,
    String energyCertificateNumber,
    String energyCertificateValidUntil,
    String energyCertificateExemptionReason,
    String youtubeVideoUrl,
    String alRegistrationNumber,
    Boolean isFeatured
) {}
