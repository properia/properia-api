package pt.properia.api.modules.listings.interfaces.request;

import java.math.BigDecimal;

public record UpdateListingRequest(
    String title,
    String descriptionRaw,
    BigDecimal priceAmount,
    int bedrooms,
    BigDecimal bathrooms,
    int suites,
    BigDecimal usableAreaM2,
    BigDecimal grossAreaM2,
    BigDecimal lotAreaM2,
    Integer floorNumber,
    Integer totalFloors,
    Integer constructionYear,
    Integer renovationYear,
    String conditionDeclared,
    String furnishedDeclared,
    String energyRating,
    String sunExposure,
    boolean hasElevator,
    boolean hasBalcony,
    boolean hasTerrace,
    boolean hasGarden,
    boolean hasPool,
    boolean hasStorageRoom,
    boolean hasGarage,
    boolean hasEquippedKitchen,
    boolean hasAirConditioning,
    boolean hasSolarPanels,
    boolean hasSeaView,
    boolean hasFireplace
) {}
