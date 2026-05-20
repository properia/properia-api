package pt.properia.api.modules.listings.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublicListingDetailDto(
    UUID id,
    String publicId,
    UUID advertiserId,
    String status,
    String businessType,
    String propertyType,
    String propertySubtype,
    String conditionFinal,
    String furnishedFinal,
    String title,
    String descriptionRaw,
    String descriptionShort,
    BigDecimal priceAmount,
    String priceCurrency,
    int bedrooms,
    BigDecimal bathrooms,
    int suites,
    int garageSpaces,
    int parkingSpaces,
    BigDecimal usableAreaM2,
    BigDecimal grossAreaM2,
    BigDecimal lotAreaM2,
    Integer floorNumber,
    Integer totalFloors,
    Integer constructionYear,
    Integer renovationYear,
    String energyRating,
    String sunExposure,
    String city,
    String district,
    String parish,
    String neighborhood,
    String postalCode,
    Double latitude,
    Double longitude,
    LocalDate availableFrom,
    boolean isImmediatelyAvailable,
    boolean hasElevator,
    boolean hasBalcony,
    boolean hasTerrace,
    boolean hasGarden,
    boolean hasPool,
    boolean hasStorageRoom,
    boolean hasGarage,
    boolean hasPrivateParking,
    boolean hasEquippedKitchen,
    boolean hasOpenKitchen,
    boolean hasOfficeSpace,
    boolean hasBuiltInClosets,
    boolean hasNaturalLight,
    boolean hasFireplace,
    boolean hasAirConditioning,
    boolean hasDoubleGlazing,
    boolean hasSolarPanels,
    boolean hasSeaView,
    boolean hasRiverView,
    boolean hasCityView,
    boolean hasGreenView,
    String poolType,
    boolean hasBarbecue,
    boolean hasLaundryArea,
    String heroImageUrl,
    Instant publishedAt,
    Instant firstPublishedAt,
    Pricing pricing,
    Location location,
    Commercial commercial,
    Features features,
    RoomDetails roomDetails,
    CommercialDetails commercialDetails,
    List<ListingMediaDto> media
) {
    public record Pricing(
        BigDecimal listPrice,
        BigDecimal rentalPrice,
        String pricePeriod,
        BigDecimal condoFee,
        BigDecimal propertyTaxAnnual,
        BigDecimal pricePerM2,
        boolean negotiable,
        boolean acceptsFinancing,
        BigDecimal depositRequired,
        BigDecimal brokerCommissionPercentage
    ) {}

    public record Location(
        String country,
        String countryCode,
        String district,
        String municipality,
        String city,
        String parish,
        String neighborhood,
        String street,
        String streetNumber,
        String postalCode,
        String displayAddress,
        Double latitude,
        Double longitude,
        String locationPrecision,
        boolean hideExactLocation
    ) {}

    public record Commercial(
        boolean exclusiveListing,
        boolean onlineVisitAvailable,
        boolean visitBookingEnabled,
        String youtubeTourUrl,
        String virtualTourUrl,
        String floorplanUrl,
        boolean showPhone,
        boolean showChat
    ) {}

    public record Features(
        String featureFlags,
        String featureTags,
        String viewTags,
        String lifestyleTags,
        String premiumSignals
    ) {}

    public record RoomDetails(
        boolean hasPrivateBathroom,
        boolean billsIncluded,
        boolean internetIncluded,
        boolean hasSharedKitchen,
        Integer totalRoomsInHouse,
        Integer currentOccupants,
        Integer minStayMonths,
        boolean coupleAllowed,
        boolean isExteriorRoom,
        String houseRulesText
    ) {}

    public record CommercialDetails(
        boolean hasShopfront,
        String streetVisibility,
        Integer internalFloors,
        boolean hasVehicleAccess,
        String permittedUse,
        boolean hasFluePipe,
        boolean hasExtractionSystem,
        boolean hasWc,
        boolean hasKitchenette,
        boolean hasOutdoorSeatingPotential
    ) {}
}
