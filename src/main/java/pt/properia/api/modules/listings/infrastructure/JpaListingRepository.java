package pt.properia.api.modules.listings.infrastructure;

import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.listings.application.ListingRepository;
import pt.properia.api.modules.listings.application.dto.ListingCardDto;
import pt.properia.api.modules.listings.application.dto.ListingMediaDto;
import pt.properia.api.modules.listings.application.dto.PublicListingDetailDto;
import pt.properia.api.modules.listings.domain.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaListingRepository implements ListingRepository {

    private final ListingJpaRepository listings;
    private final ListingMediaJpaRepository media;
    private final ListingLocationJpaRepository locations;
    private final ListingPricingJpaRepository pricing;
    private final ListingCommercialJpaRepository commercial;
    private final ListingFeaturesJpaRepository features;
    private final ListingRoomDetailsJpaRepository roomDetails;
    private final ListingCommercialDetailsJpaRepository commercialDetails;
    private final JdbcClient jdbc;

    public JpaListingRepository(
            ListingJpaRepository listings,
            ListingMediaJpaRepository media,
            ListingLocationJpaRepository locations,
            ListingPricingJpaRepository pricing,
            ListingCommercialJpaRepository commercial,
            ListingFeaturesJpaRepository features,
            ListingRoomDetailsJpaRepository roomDetails,
            ListingCommercialDetailsJpaRepository commercialDetails,
            JdbcClient jdbc) {
        this.listings = listings;
        this.media = media;
        this.locations = locations;
        this.pricing = pricing;
        this.commercial = commercial;
        this.features = features;
        this.roomDetails = roomDetails;
        this.commercialDetails = commercialDetails;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PublicListingDetailDto> findPublishedByPublicId(String publicId) {
        Optional<Listing> found;
        try {
            found = listings.findById(UUID.fromString(publicId))
                .filter(l -> "published".equals(l.getStatus()));
        } catch (IllegalArgumentException e) {
            found = listings.findByPublicId(publicId)
                .filter(l -> "published".equals(l.getStatus()));
        }
        return found.map(this::toDetailDto);
    }

    @Override
    public List<ListingCardDto> findByAdvertiserId(UUID advertiserId) {
        return jdbc.sql("""
                SELECT l.id, l.public_id, l.advertiser_id, l.status::text, l.business_type::text,
                       l.property_type::text, l.title, l.price_amount, l.price_currency,
                       l.bedrooms, l.bathrooms, l.suites, l.usable_area_m2, l.gross_area_m2,
                       l.city, l.district, l.parish, l.neighborhood, l.postal_code,
                       COALESCE(loc.street, '') AS street,
                       l.hero_image_url, l.energy_rating,
                       l.condition_declared::text, l.furnished_declared::text,
                       COALESCE(l.latitude, loc.latitude)   AS latitude,
                       COALESCE(l.longitude, loc.longitude) AS longitude,
                       zs.zone_label_primary, zs.zone_summary_short,
                       l.published_at, l.first_published_at, l.created_at
                FROM properia.listings l
                LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id
                LEFT JOIN properia.listing_zone_scores zs  ON zs.listing_id  = l.id
                WHERE l.advertiser_id = :adv
                  AND l.status::text != 'archived'
                ORDER BY l.created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new ListingCardDto(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("public_id"),
                rs.getObject("advertiser_id", java.util.UUID.class),
                rs.getString("status"),
                rs.getString("business_type"),
                rs.getString("property_type"),
                rs.getString("title"),
                rs.getBigDecimal("price_amount"),
                rs.getString("price_currency"),
                rs.getInt("bedrooms"),
                rs.getBigDecimal("bathrooms"),
                rs.getInt("suites"),
                rs.getBigDecimal("usable_area_m2"),
                rs.getBigDecimal("gross_area_m2"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getString("parish"),
                rs.getString("neighborhood"),
                rs.getString("street"),
                rs.getString("postal_code"),
                rs.getString("hero_image_url"),
                rs.getString("energy_rating"),
                rs.getString("condition_declared"),
                rs.getString("furnished_declared"),
                rs.getObject("latitude") != null ? rs.getDouble("latitude") : null,
                rs.getObject("longitude") != null ? rs.getDouble("longitude") : null,
                rs.getString("zone_label_primary"),
                rs.getString("zone_summary_short"),
                toInstant(rs.getTimestamp("published_at")),
                toInstant(rs.getTimestamp("first_published_at")),
                toInstant(rs.getTimestamp("created_at"))
            ))
            .list();
    }

    private static java.time.Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<Listing> findByIdAndAdvertiserId(UUID id, UUID advertiserId) {
        return listings.findByIdAndAdvertiserId(id, advertiserId);
    }

    @Override
    public Listing save(Listing listing) {
        return listings.save(listing);
    }

    @Override
    public void archive(UUID id, UUID advertiserId) {
        listings.findByIdAndAdvertiserId(id, advertiserId).ifPresent(l -> {
            l.setStatus("archived");
            listings.save(l);
        });
    }

    @Override
    public void saveSubEntities(SaveSubEntitiesInput input) {
        if (input.pricing() != null) {
            savePricing(input.listingId(), input.pricing());
        }
        if (input.location() != null) {
            saveLocation(input.listingId(), input.location());
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private PublicListingDetailDto toDetailDto(Listing l) {
        var p = pricing.findByListingId(l.getId()).orElse(null);
        var loc = locations.findByListingId(l.getId()).orElse(null);
        var com = commercial.findByListingId(l.getId()).orElse(null);
        var feat = features.findByListingId(l.getId()).orElse(null);
        var room = roomDetails.findByListingId(l.getId()).orElse(null);
        var comDet = commercialDetails.findByListingId(l.getId()).orElse(null);
        var mediaList = media.findByListingIdOrderBySortOrderAsc(l.getId());

        return new PublicListingDetailDto(
            l.getId(), l.getPublicId(), l.getAdvertiserId(),
            l.getStatus(), l.getBusinessType(), l.getPropertyType(), l.getPropertySubtype(),
            l.getConditionFinal(), l.getFurnishedFinal(),
            l.getTitle(), l.getDescriptionRaw(), l.getDescriptionShort(),
            l.getPriceAmount(), l.getPriceCurrency(),
            l.getBedrooms(), l.getBathrooms(), l.getSuites(),
            l.getGarageSpaces(), l.getParkingSpaces(),
            l.getUsableAreaM2(), l.getGrossAreaM2(), l.getLotAreaM2(),
            l.getFloorNumber(), l.getTotalFloors(),
            l.getConstructionYear(), l.getRenovationYear(),
            l.getEnergyRating(), l.getSunExposure(),
            l.getCity(), l.getDistrict(), l.getParish(), l.getNeighborhood(), l.getPostalCode(),
            l.getLatitude() != null ? l.getLatitude() : (loc != null ? loc.getLatitude() : null),
            l.getLongitude() != null ? l.getLongitude() : (loc != null ? loc.getLongitude() : null),
            l.getAvailableFrom(), l.isImmediatelyAvailable(),
            l.isHasElevator(), l.isHasBalcony(), l.isHasTerrace(), l.isHasGarden(),
            l.isHasPool(), l.isHasStorageRoom(), l.isHasGarage(), l.isHasPrivateParking(),
            l.isHasEquippedKitchen(), l.isHasOpenKitchen(), l.isHasOfficeSpace(), l.isHasBuiltInClosets(),
            l.isHasNaturalLight(), l.isHasFireplace(), l.isHasAirConditioning(), l.isHasDoubleGlazing(),
            l.isHasSolarPanels(), l.isHasSeaView(), l.isHasRiverView(), l.isHasCityView(), l.isHasGreenView(),
            l.getPoolType(), l.isHasBarbecue(), l.isHasLaundryArea(),
            l.getHeroImageUrl(), l.getPublishedAt(), l.getFirstPublishedAt(),
            toPricingDto(p),
            toLocationDto(loc),
            toCommercialDto(com),
            toFeaturesDto(feat),
            toRoomDetailsDto(room),
            toCommercialDetailsDto(comDet),
            mediaList.stream().map(this::toMediaDto).toList()
        );
    }

    private ListingCardDto toCardDto(Listing l) {
        return new ListingCardDto(
            l.getId(), l.getPublicId(), l.getAdvertiserId(), l.getStatus(),
            l.getBusinessType(), l.getPropertyType(), l.getTitle(),
            l.getPriceAmount(), l.getPriceCurrency(),
            l.getBedrooms(), l.getBathrooms(), l.getSuites(),
            l.getUsableAreaM2(), l.getGrossAreaM2(),
            l.getCity(), l.getDistrict(), l.getParish(), l.getNeighborhood(),
            null, l.getPostalCode(),
            l.getHeroImageUrl(), l.getEnergyRating(),
            l.getConditionDeclared(), l.getFurnishedDeclared(),
            l.getLatitude(), l.getLongitude(),
            null, null,
            l.getPublishedAt(), l.getFirstPublishedAt(), l.getCreatedAt()
        );
    }

    private PublicListingDetailDto.Pricing toPricingDto(ListingPricing p) {
        if (p == null) return null;
        return new PublicListingDetailDto.Pricing(
            p.getListPrice(), p.getRentalPrice(), p.getPricePeriod(),
            p.getCondoFee(), p.getPropertyTaxAnnual(), p.getPricePerM2(),
            p.isNegotiable(), p.isAcceptsFinancing(),
            p.getDepositRequired(), p.getBrokerCommissionPercentage()
        );
    }

    private PublicListingDetailDto.Location toLocationDto(ListingLocation loc) {
        if (loc == null) return null;
        return new PublicListingDetailDto.Location(
            loc.getCountry(), loc.getCountryCode(), loc.getDistrict(), loc.getMunicipality(),
            loc.getCity(), loc.getParish(), loc.getNeighborhood(),
            loc.getStreet(), loc.getStreetNumber(), loc.getPostalCode(),
            loc.getDisplayAddress(), loc.getLatitude(), loc.getLongitude(),
            loc.getLocationPrecision(), loc.isHideExactLocation()
        );
    }

    private PublicListingDetailDto.Commercial toCommercialDto(ListingCommercial com) {
        if (com == null) return null;
        return new PublicListingDetailDto.Commercial(
            com.isExclusiveListing(), com.isOnlineVisitAvailable(), com.isVisitBookingEnabled(),
            com.getYoutubeTourUrl(), com.getVirtualTourUrl(), com.getFloorplanUrl(),
            com.isShowPhone(), com.isShowChat()
        );
    }

    private PublicListingDetailDto.Features toFeaturesDto(ListingFeatures feat) {
        if (feat == null) return null;
        return new PublicListingDetailDto.Features(
            feat.getFeatureFlags(), feat.getFeatureTags(),
            feat.getViewTags(), feat.getLifestyleTags(), feat.getPremiumSignals()
        );
    }

    private PublicListingDetailDto.RoomDetails toRoomDetailsDto(ListingRoomDetails room) {
        if (room == null) return null;
        return new PublicListingDetailDto.RoomDetails(
            room.isHasPrivateBathroom(), room.isBillsIncluded(), room.isInternetIncluded(),
            room.isHasSharedKitchen(), room.getTotalRoomsInHouse(), room.getCurrentOccupants(),
            room.getMinStayMonths(), room.isCoupleAllowed(), room.isExteriorRoom(), room.getHouseRulesText()
        );
    }

    private PublicListingDetailDto.CommercialDetails toCommercialDetailsDto(ListingCommercialDetails det) {
        if (det == null) return null;
        return new PublicListingDetailDto.CommercialDetails(
            det.isHasShopfront(), det.getStreetVisibility(), det.getInternalFloors(),
            det.isHasVehicleAccess(), det.getPermittedUse(), det.isHasFluePipe(),
            det.isHasExtractionSystem(), det.isHasWc(), det.isHasKitchenette(),
            det.isHasOutdoorSeatingPotential()
        );
    }

    private ListingMediaDto toMediaDto(ListingMedia m) {
        return new ListingMediaDto(
            m.getId(), m.getMediaType(), m.getUrl(), m.getThumbnailUrl(),
            m.getSortOrder(), m.isCover(), m.getCaption(), m.getRoomHint()
        );
    }

    // ── Sub-entity persistence ─────────────────────────────────────────────────

    private void savePricing(UUID listingId, SavePricingInput input) {
        jdbc.sql("""
            INSERT INTO properia.listing_pricing
              (listing_id, list_price, rental_price, price_period, condo_fee,
               deposit_required, negotiable, accepts_financing, updated_at)
            VALUES
              (:listingId, :listPrice, :rentalPrice, :pricePeriod::properia.price_period,
               :condoFee, :depositRequired, :negotiable, :acceptsFinancing, now())
            ON CONFLICT (listing_id) DO UPDATE SET
              list_price = EXCLUDED.list_price,
              rental_price = EXCLUDED.rental_price,
              price_period = EXCLUDED.price_period,
              condo_fee = EXCLUDED.condo_fee,
              deposit_required = EXCLUDED.deposit_required,
              negotiable = EXCLUDED.negotiable,
              accepts_financing = EXCLUDED.accepts_financing,
              updated_at = now()
            """)
            .param("listingId", listingId)
            .param("listPrice", input.listPrice())
            .param("rentalPrice", input.rentalPrice())
            .param("pricePeriod", input.pricePeriod() != null ? input.pricePeriod() : "sale")
            .param("condoFee", input.condoFee())
            .param("depositRequired", input.depositRequired())
            .param("negotiable", input.negotiable())
            .param("acceptsFinancing", input.acceptsFinancing())
            .update();
    }

    private void saveLocation(UUID listingId, SaveLocationInput input) {
        jdbc.sql("""
            INSERT INTO properia.listing_location
              (listing_id, city, district, municipality, parish, neighborhood,
               street, street_number, postal_code, latitude, longitude,
               location_precision, hide_exact_location, updated_at)
            VALUES
              (:listingId, :city, :district, :municipality, :parish, :neighborhood,
               :street, :streetNumber, :postalCode, :latitude, :longitude,
               :locationPrecision::properia.location_precision, :hideExactLocation, now())
            ON CONFLICT (listing_id) DO UPDATE SET
              city = EXCLUDED.city,
              district = EXCLUDED.district,
              municipality = EXCLUDED.municipality,
              parish = EXCLUDED.parish,
              neighborhood = EXCLUDED.neighborhood,
              street = EXCLUDED.street,
              street_number = EXCLUDED.street_number,
              postal_code = EXCLUDED.postal_code,
              latitude = EXCLUDED.latitude,
              longitude = EXCLUDED.longitude,
              location_precision = EXCLUDED.location_precision,
              hide_exact_location = EXCLUDED.hide_exact_location,
              updated_at = now()
            """)
            .param("listingId", listingId)
            .param("city", input.city())
            .param("district", input.district())
            .param("municipality", input.municipality())
            .param("parish", input.parish())
            .param("neighborhood", input.neighborhood())
            .param("street", input.street())
            .param("streetNumber", input.streetNumber())
            .param("postalCode", input.postalCode())
            .param("latitude", input.latitude())
            .param("longitude", input.longitude())
            .param("locationPrecision", input.locationPrecision())
            .param("hideExactLocation", input.hideExactLocation())
            .update();
    }
}
