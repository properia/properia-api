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
    BigDecimal priceAmount,
    Integer bedrooms,
    BigDecimal bathrooms,
    Integer suites,
    BigDecimal usableAreaM2,
    BigDecimal grossAreaM2,
    String city,
    String district,
    String parish,
    String postalCode,
    String conditionDeclared,
    String furnishedDeclared,
    Boolean isFeatured
) {}
