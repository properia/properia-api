package pt.properia.api.modules.listings.application;

import pt.properia.api.modules.listings.application.dto.ListingCardDto;
import pt.properia.api.modules.listings.application.dto.PublicListingDetailDto;
import pt.properia.api.modules.listings.domain.Listing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository {

    Optional<PublicListingDetailDto> findPublishedByPublicId(String publicId);

    List<ListingCardDto> findByAdvertiserId(UUID advertiserId);

    Optional<Listing> findByIdAndAdvertiserId(UUID id, UUID advertiserId);

    Listing save(Listing listing);

    void archive(UUID id, UUID advertiserId);

    record SaveSubEntitiesInput(
        UUID listingId,
        SavePricingInput pricing,
        SaveLocationInput location
    ) {}

    record SavePricingInput(
        java.math.BigDecimal listPrice,
        java.math.BigDecimal rentalPrice,
        String pricePeriod,
        java.math.BigDecimal condoFee,
        java.math.BigDecimal depositRequired,
        java.math.BigDecimal propertyTaxAnnual,
        java.math.BigDecimal maintenanceCostEstimate,
        boolean negotiable,
        boolean acceptsFinancing
    ) {}

    record SaveLocationInput(
        String city,
        String district,
        String municipality,
        String parish,
        String neighborhood,
        String street,
        String streetNumber,
        String postalCode,
        Double latitude,
        Double longitude,
        String locationPrecision,
        boolean hideExactLocation
    ) {}

    void saveSubEntities(SaveSubEntitiesInput input);
}
