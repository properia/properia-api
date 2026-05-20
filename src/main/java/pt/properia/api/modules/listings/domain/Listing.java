package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "listings", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.listing_status")
    private String status = "draft";

    @Column(name = "business_type", nullable = false)
    @ColumnTransformer(write = "?::properia.business_type")
    private String businessType;

    @Column(name = "property_type", nullable = false)
    @ColumnTransformer(write = "?::properia.property_type")
    private String propertyType;

    @Column(name = "property_subtype")
    private String propertySubtype;

    @Column(name = "condition_declared")
    @ColumnTransformer(write = "?::properia.condition_status")
    private String conditionDeclared;

    @Column(name = "condition_final")
    @ColumnTransformer(write = "?::properia.condition_status")
    private String conditionFinal;

    @Column(name = "furnished_declared")
    @ColumnTransformer(write = "?::properia.furnished_status")
    private String furnishedDeclared;

    @Column(name = "furnished_final")
    @ColumnTransformer(write = "?::properia.furnished_status")
    private String furnishedFinal;

    @Column(nullable = false)
    private String title;

    @Column(name = "title_normalized", nullable = false)
    private String titleNormalized;

    @Column(name = "description_raw", columnDefinition = "text")
    private String descriptionRaw;

    @Column(name = "description_short", columnDefinition = "text")
    private String descriptionShort;

    @Column(name = "price_amount", precision = 14, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", nullable = false)
    private String priceCurrency = "EUR";

    @Column(nullable = false)
    private int bedrooms = 0;

    @Column(precision = 4, scale = 1, nullable = false)
    private BigDecimal bathrooms = BigDecimal.ZERO;

    @Column(nullable = false)
    private int suites = 0;

    @Column(name = "garage_spaces", nullable = false)
    private int garageSpaces = 0;

    @Column(name = "parking_spaces", nullable = false)
    private int parkingSpaces = 0;

    @Column(name = "usable_area_m2", precision = 10, scale = 2)
    private BigDecimal usableAreaM2;

    @Column(name = "gross_area_m2", precision = 10, scale = 2)
    private BigDecimal grossAreaM2;

    @Column(name = "lot_area_m2", precision = 10, scale = 2)
    private BigDecimal lotAreaM2;

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "total_floors")
    private Integer totalFloors;

    @Column(name = "construction_year")
    private Integer constructionYear;

    @Column(name = "renovation_year")
    private Integer renovationYear;

    @Column(name = "energy_rating")
    private String energyRating;

    @Column(name = "sun_exposure")
    private String sunExposure;

    @Column
    private String city;

    @Column
    private String district;

    @Column
    private String parish;

    @Column
    private String neighborhood;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "country_code", nullable = false)
    private String countryCode = "PT";

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "is_immediately_available", nullable = false)
    private boolean isImmediatelyAvailable = false;

    @Column(name = "has_elevator", nullable = false)
    private boolean hasElevator = false;

    @Column(name = "has_balcony", nullable = false)
    private boolean hasBalcony = false;

    @Column(name = "has_terrace", nullable = false)
    private boolean hasTerrace = false;

    @Column(name = "has_garden", nullable = false)
    private boolean hasGarden = false;

    @Column(name = "has_pool", nullable = false)
    private boolean hasPool = false;

    @Column(name = "has_storage_room", nullable = false)
    private boolean hasStorageRoom = false;

    @Column(name = "has_garage", nullable = false)
    private boolean hasGarage = false;

    @Column(name = "has_private_parking", nullable = false)
    private boolean hasPrivateParking = false;

    @Column(name = "has_equipped_kitchen", nullable = false)
    private boolean hasEquippedKitchen = false;

    @Column(name = "has_open_kitchen", nullable = false)
    private boolean hasOpenKitchen = false;

    @Column(name = "has_office_space", nullable = false)
    private boolean hasOfficeSpace = false;

    @Column(name = "has_built_in_closets", nullable = false)
    private boolean hasBuiltInClosets = false;

    @Column(name = "has_natural_light", nullable = false)
    private boolean hasNaturalLight = false;

    @Column(name = "has_fireplace", nullable = false)
    private boolean hasFireplace = false;

    @Column(name = "has_air_conditioning", nullable = false)
    private boolean hasAirConditioning = false;

    @Column(name = "has_double_glazing", nullable = false)
    private boolean hasDoubleGlazing = false;

    @Column(name = "has_solar_panels", nullable = false)
    private boolean hasSolarPanels = false;

    @Column(name = "has_sea_view", nullable = false)
    private boolean hasSeaView = false;

    @Column(name = "has_river_view", nullable = false)
    private boolean hasRiverView = false;

    @Column(name = "has_city_view", nullable = false)
    private boolean hasCityView = false;

    @Column(name = "has_green_view", nullable = false)
    private boolean hasGreenView = false;

    @Column(name = "pool_type")
    private String poolType;

    @Column(name = "has_barbecue", nullable = false)
    private boolean hasBarbecue = false;

    @Column(name = "has_laundry_area", nullable = false)
    private boolean hasLaundryArea = false;

    @Column(name = "hero_image_url")
    private String heroImageUrl;

    @Column(name = "visibility_status", nullable = false)
    @ColumnTransformer(write = "?::properia.visibility_status")
    private String visibilityStatus = "organic";

    @Column(name = "is_featured", nullable = false)
    private boolean isFeatured = false;

    @Column(name = "is_premium", nullable = false)
    private boolean isPremium = false;

    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Listing() {}

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getPublicId() { return publicId; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getStatus() { return status; }
    public String getBusinessType() { return businessType; }
    public String getPropertyType() { return propertyType; }
    public String getPropertySubtype() { return propertySubtype; }
    public String getConditionDeclared() { return conditionDeclared; }
    public String getConditionFinal() { return conditionFinal; }
    public String getFurnishedDeclared() { return furnishedDeclared; }
    public String getFurnishedFinal() { return furnishedFinal; }
    public String getTitle() { return title; }
    public String getTitleNormalized() { return titleNormalized; }
    public String getDescriptionRaw() { return descriptionRaw; }
    public String getDescriptionShort() { return descriptionShort; }
    public BigDecimal getPriceAmount() { return priceAmount; }
    public String getPriceCurrency() { return priceCurrency; }
    public int getBedrooms() { return bedrooms; }
    public BigDecimal getBathrooms() { return bathrooms; }
    public int getSuites() { return suites; }
    public int getGarageSpaces() { return garageSpaces; }
    public int getParkingSpaces() { return parkingSpaces; }
    public BigDecimal getUsableAreaM2() { return usableAreaM2; }
    public BigDecimal getGrossAreaM2() { return grossAreaM2; }
    public BigDecimal getLotAreaM2() { return lotAreaM2; }
    public Integer getFloorNumber() { return floorNumber; }
    public Integer getTotalFloors() { return totalFloors; }
    public Integer getConstructionYear() { return constructionYear; }
    public Integer getRenovationYear() { return renovationYear; }
    public String getEnergyRating() { return energyRating; }
    public String getSunExposure() { return sunExposure; }
    public String getCity() { return city; }
    public String getDistrict() { return district; }
    public String getParish() { return parish; }
    public String getNeighborhood() { return neighborhood; }
    public String getPostalCode() { return postalCode; }
    public String getCountryCode() { return countryCode; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public LocalDate getAvailableFrom() { return availableFrom; }
    public boolean isImmediatelyAvailable() { return isImmediatelyAvailable; }
    public boolean isHasElevator() { return hasElevator; }
    public boolean isHasBalcony() { return hasBalcony; }
    public boolean isHasTerrace() { return hasTerrace; }
    public boolean isHasGarden() { return hasGarden; }
    public boolean isHasPool() { return hasPool; }
    public boolean isHasStorageRoom() { return hasStorageRoom; }
    public boolean isHasGarage() { return hasGarage; }
    public boolean isHasPrivateParking() { return hasPrivateParking; }
    public boolean isHasEquippedKitchen() { return hasEquippedKitchen; }
    public boolean isHasOpenKitchen() { return hasOpenKitchen; }
    public boolean isHasOfficeSpace() { return hasOfficeSpace; }
    public boolean isHasBuiltInClosets() { return hasBuiltInClosets; }
    public boolean isHasNaturalLight() { return hasNaturalLight; }
    public boolean isHasFireplace() { return hasFireplace; }
    public boolean isHasAirConditioning() { return hasAirConditioning; }
    public boolean isHasDoubleGlazing() { return hasDoubleGlazing; }
    public boolean isHasSolarPanels() { return hasSolarPanels; }
    public boolean isHasSeaView() { return hasSeaView; }
    public boolean isHasRiverView() { return hasRiverView; }
    public boolean isHasCityView() { return hasCityView; }
    public boolean isHasGreenView() { return hasGreenView; }
    public String getPoolType() { return poolType; }
    public boolean isHasBarbecue() { return hasBarbecue; }
    public boolean isHasLaundryArea() { return hasLaundryArea; }
    public String getHeroImageUrl() { return heroImageUrl; }
    public String getVisibilityStatus() { return visibilityStatus; }
    public boolean isFeatured() { return isFeatured; }
    public boolean isPremium() { return isPremium; }
    public Instant getFirstPublishedAt() { return firstPublishedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setPublicId(String publicId) { this.publicId = publicId; }
    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setStatus(String status) { this.status = status; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
    public void setPropertySubtype(String propertySubtype) { this.propertySubtype = propertySubtype; }
    public void setConditionDeclared(String conditionDeclared) { this.conditionDeclared = conditionDeclared; }
    public void setFurnishedDeclared(String furnishedDeclared) { this.furnishedDeclared = furnishedDeclared; }
    public void setTitle(String title) { this.title = title; }
    public void setTitleNormalized(String titleNormalized) { this.titleNormalized = titleNormalized; }
    public void setDescriptionRaw(String descriptionRaw) { this.descriptionRaw = descriptionRaw; }
    public void setDescriptionShort(String descriptionShort) { this.descriptionShort = descriptionShort; }
    public void setPriceAmount(BigDecimal priceAmount) { this.priceAmount = priceAmount; }
    public void setBedrooms(int bedrooms) { this.bedrooms = bedrooms; }
    public void setBathrooms(BigDecimal bathrooms) { this.bathrooms = bathrooms; }
    public void setSuites(int suites) { this.suites = suites; }
    public void setGarageSpaces(int garageSpaces) { this.garageSpaces = garageSpaces; }
    public void setParkingSpaces(int parkingSpaces) { this.parkingSpaces = parkingSpaces; }
    public void setUsableAreaM2(BigDecimal usableAreaM2) { this.usableAreaM2 = usableAreaM2; }
    public void setGrossAreaM2(BigDecimal grossAreaM2) { this.grossAreaM2 = grossAreaM2; }
    public void setLotAreaM2(BigDecimal lotAreaM2) { this.lotAreaM2 = lotAreaM2; }
    public void setFloorNumber(Integer floorNumber) { this.floorNumber = floorNumber; }
    public void setTotalFloors(Integer totalFloors) { this.totalFloors = totalFloors; }
    public void setConstructionYear(Integer constructionYear) { this.constructionYear = constructionYear; }
    public void setRenovationYear(Integer renovationYear) { this.renovationYear = renovationYear; }
    public void setEnergyRating(String energyRating) { this.energyRating = energyRating; }
    public void setSunExposure(String sunExposure) { this.sunExposure = sunExposure; }
    public void setCity(String city) { this.city = city; }
    public void setDistrict(String district) { this.district = district; }
    public void setParish(String parish) { this.parish = parish; }
    public void setNeighborhood(String neighborhood) { this.neighborhood = neighborhood; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setAvailableFrom(LocalDate availableFrom) { this.availableFrom = availableFrom; }
    public void setImmediatelyAvailable(boolean immediatelyAvailable) { isImmediatelyAvailable = immediatelyAvailable; }
    public void setHasElevator(boolean hasElevator) { this.hasElevator = hasElevator; }
    public void setHasBalcony(boolean hasBalcony) { this.hasBalcony = hasBalcony; }
    public void setHasTerrace(boolean hasTerrace) { this.hasTerrace = hasTerrace; }
    public void setHasGarden(boolean hasGarden) { this.hasGarden = hasGarden; }
    public void setHasPool(boolean hasPool) { this.hasPool = hasPool; }
    public void setHasStorageRoom(boolean hasStorageRoom) { this.hasStorageRoom = hasStorageRoom; }
    public void setHasGarage(boolean hasGarage) { this.hasGarage = hasGarage; }
    public void setHasPrivateParking(boolean hasPrivateParking) { this.hasPrivateParking = hasPrivateParking; }
    public void setHasEquippedKitchen(boolean hasEquippedKitchen) { this.hasEquippedKitchen = hasEquippedKitchen; }
    public void setHasOpenKitchen(boolean hasOpenKitchen) { this.hasOpenKitchen = hasOpenKitchen; }
    public void setHasOfficeSpace(boolean hasOfficeSpace) { this.hasOfficeSpace = hasOfficeSpace; }
    public void setHasBuiltInClosets(boolean hasBuiltInClosets) { this.hasBuiltInClosets = hasBuiltInClosets; }
    public void setHasNaturalLight(boolean hasNaturalLight) { this.hasNaturalLight = hasNaturalLight; }
    public void setHasFireplace(boolean hasFireplace) { this.hasFireplace = hasFireplace; }
    public void setHasAirConditioning(boolean hasAirConditioning) { this.hasAirConditioning = hasAirConditioning; }
    public void setHasDoubleGlazing(boolean hasDoubleGlazing) { this.hasDoubleGlazing = hasDoubleGlazing; }
    public void setHasSolarPanels(boolean hasSolarPanels) { this.hasSolarPanels = hasSolarPanels; }
    public void setHasSeaView(boolean hasSeaView) { this.hasSeaView = hasSeaView; }
    public void setHasRiverView(boolean hasRiverView) { this.hasRiverView = hasRiverView; }
    public void setHasCityView(boolean hasCityView) { this.hasCityView = hasCityView; }
    public void setHasGreenView(boolean hasGreenView) { this.hasGreenView = hasGreenView; }
    public void setPoolType(String poolType) { this.poolType = poolType; }
    public void setHasBarbecue(boolean hasBarbecue) { this.hasBarbecue = hasBarbecue; }
    public void setHasLaundryArea(boolean hasLaundryArea) { this.hasLaundryArea = hasLaundryArea; }
    public void setHeroImageUrl(String heroImageUrl) { this.heroImageUrl = heroImageUrl; }
    public void setVisibilityStatus(String visibilityStatus) { this.visibilityStatus = visibilityStatus; }
    public void setFeatured(boolean featured) { isFeatured = featured; }
    public void setPremium(boolean premium) { isPremium = premium; }
    public void setFirstPublishedAt(Instant firstPublishedAt) { this.firstPublishedAt = firstPublishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
