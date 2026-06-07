package pt.properia.api.modules.listings.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.zone.application.ZoneSnapshotService;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class PatchListingService {

    private final ListingRepository repository;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ZoneSnapshotService zoneSnapshotService;

    public PatchListingService(ListingRepository repository, JdbcClient jdbc, ObjectMapper json,
                                ZoneSnapshotService zoneSnapshotService) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
        this.zoneSnapshotService = zoneSnapshotService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> patch(UUID listingId, UUID advertiserId, Map<String, Object> body) {
        var listing = repository.findByIdAndAdvertiserId(listingId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));
        final BigDecimal priceBeforePatch = listing.getPriceAmount();

        // ── Status ────────────────────────────────────────────────────────────
        if (body.containsKey("status")) {
            var newStatus = str(body, "status");
            switch (newStatus != null ? newStatus : "") {
                case "published" -> {
                    if ("archived".equals(listing.getStatus()))
                        throw new DomainException("INVALID_STATUS", "Um anúncio arquivado não pode ser publicado.", 409);
                    var now = Instant.now();
                    listing.setStatus("published");
                    listing.setPublishedAt(now);
                    if (listing.getFirstPublishedAt() == null) listing.setFirstPublishedAt(now);
                }
                case "archived" -> listing.setStatus("archived");
                case "draft" -> {
                    if ("archived".equals(listing.getStatus()))
                        throw new DomainException("INVALID_STATUS", "Um anúncio arquivado não pode ser reactivado.", 409);
                    listing.setStatus("draft");
                }
                case "paused" -> listing.setStatus("paused");
                case "pending_review" -> listing.setStatus("pending_review");
                default -> throw new DomainException("BAD_REQUEST", "Status inválido: " + newStatus, 400);
            }
        }

        // ── Agent assignment ──────────────────────────────────────────────────
        if (body.containsKey("assignedAgentId")) {
            var agentId = str(body, "assignedAgentId");
            listing.setOwnerUserId(agentId != null && !agentId.isBlank() ? UUID.fromString(agentId) : null);
        }

        // ── Core content ──────────────────────────────────────────────────────
        if (body.containsKey("title")) {
            var t = str(body, "title");
            if (t != null && !t.isBlank()) {
                listing.setTitle(t.strip());
                listing.setTitleNormalized(t.strip().toLowerCase());
            }
        }
        if (body.containsKey("businessType")) {
            var bt = str(body, "businessType");
            if (bt != null) listing.setBusinessType(bt);
        }
        if (body.containsKey("propertyType")) {
            var pt = str(body, "propertyType");
            if (pt != null) listing.setPropertyType(pt);
        }
        if (body.containsKey("descriptionRaw")) listing.setDescriptionRaw(str(body, "descriptionRaw"));
        if (body.containsKey("descriptionShort")) listing.setDescriptionShort(str(body, "descriptionShort"));
        if (body.containsKey("heroImageUrl")) listing.setHeroImageUrl(str(body, "heroImageUrl"));
        if (body.containsKey("alRegistrationNumber")) listing.setAlRegistrationNumber(str(body, "alRegistrationNumber"));
        if (body.containsKey("priceAmount")) listing.setPriceAmount(decimal(body, "priceAmount"));
        if (body.containsKey("bedrooms")) listing.setBedrooms(intVal(body, "bedrooms"));
        if (body.containsKey("bathrooms")) {
            var b = decimal(body, "bathrooms");
            listing.setBathrooms(b != null ? b : BigDecimal.ZERO);
        }
        if (body.containsKey("suites")) listing.setSuites(intVal(body, "suites"));
        if (body.containsKey("garageSpaces")) listing.setGarageSpaces(intVal(body, "garageSpaces"));
        if (body.containsKey("parkingSpaces")) listing.setParkingSpaces(intVal(body, "parkingSpaces"));
        if (body.containsKey("usableAreaM2")) listing.setUsableAreaM2(decimal(body, "usableAreaM2"));
        if (body.containsKey("grossAreaM2")) listing.setGrossAreaM2(decimal(body, "grossAreaM2"));
        if (body.containsKey("lotAreaM2")) listing.setLotAreaM2(decimal(body, "lotAreaM2"));
        if (body.containsKey("floorNumber")) listing.setFloorNumber(intOrNull(body, "floorNumber"));
        if (body.containsKey("totalFloors")) listing.setTotalFloors(intOrNull(body, "totalFloors"));
        if (body.containsKey("constructionYear")) listing.setConstructionYear(intOrNull(body, "constructionYear"));
        if (body.containsKey("renovationYear")) listing.setRenovationYear(intOrNull(body, "renovationYear"));
        if (body.containsKey("energyRating")) listing.setEnergyRating(str(body, "energyRating"));
        if (body.containsKey("sunExposure")) listing.setSunExposure(str(body, "sunExposure"));
        if (body.containsKey("conditionStatus")) listing.setConditionDeclared(str(body, "conditionStatus"));
        if (body.containsKey("furnishedStatus")) listing.setFurnishedDeclared(str(body, "furnishedStatus"));
        if (body.containsKey("isFeatured")) listing.setFeatured(bool(body, "isFeatured"));
        if (body.containsKey("isImmediatelyAvailable")) listing.setImmediatelyAvailable(bool(body, "isImmediatelyAvailable"));
        if (body.containsKey("availableFrom")) {
            var d = str(body, "availableFrom");
            listing.setAvailableFrom(d != null && !d.isBlank() ? LocalDate.parse(d.substring(0, 10)) : null);
        }

        // ── Location on main table ────────────────────────────────────────────
        if (body.containsKey("city")) listing.setCity(str(body, "city"));
        if (body.containsKey("district")) listing.setDistrict(str(body, "district"));
        if (body.containsKey("parish")) listing.setParish(str(body, "parish"));
        if (body.containsKey("neighborhood")) listing.setNeighborhood(str(body, "neighborhood"));
        if (body.containsKey("postalCode")) listing.setPostalCode(str(body, "postalCode"));
        if (body.containsKey("latitude")) listing.setLatitude(doubleOrNull(body, "latitude"));
        if (body.containsKey("longitude")) listing.setLongitude(doubleOrNull(body, "longitude"));

        var saved = repository.save(listing);

        // ── Price history ─────────────────────────────────────────────────────
        if (body.containsKey("priceAmount") && saved.getPriceAmount() != null) {
            boolean changed = priceBeforePatch == null
                || priceBeforePatch.compareTo(saved.getPriceAmount()) != 0;
            if (changed) {
                jdbc.sql("""
                    INSERT INTO properia.listing_price_history
                      (listing_id, price_amount, price_currency)
                    VALUES (:lid, :price, :currency)
                    """)
                    .param("lid",      saved.getId())
                    .param("price",    saved.getPriceAmount())
                    .param("currency", saved.getPriceCurrency() != null ? saved.getPriceCurrency() : "EUR")
                    .update();
            }
        }

        // ── Zone snapshot trigger ─────────────────────────────────────────────
        // Trigger async zone processing when listing is published with coordinates.
        // Also re-triggers if coordinates changed while already published.
        boolean nowPublished = "published".equals(saved.getStatus());
        boolean hasCoords    = saved.getLatitude() != null && saved.getLongitude() != null;
        boolean coordsOrStatusChanged = body.containsKey("status") || body.containsKey("latitude") || body.containsKey("longitude");
        if (nowPublished && hasCoords && coordsOrStatusChanged) {
            var locSnap = getLocationForZone(saved.getId());
            zoneSnapshotService.processAsync(
                saved.getId(),
                saved.getLatitude(),
                saved.getLongitude(),
                locSnap.get("street"),
                locSnap.get("neighborhood") != null ? locSnap.get("neighborhood") : saved.getNeighborhood(),
                saved.getCity(),
                locSnap.get("precision")
            );
        }

        // ── Location sub-entity ───────────────────────────────────────────────
        boolean hasLocationFields = body.containsKey("city") || body.containsKey("district")
            || body.containsKey("latitude") || body.containsKey("longitude")
            || body.containsKey("postalCode") || body.containsKey("parish")
            || body.containsKey("street") || body.containsKey("neighborhood");

        if (hasLocationFields) {
            var precision = str(body, "locationPrecision");
            repository.saveSubEntities(new ListingRepository.SaveSubEntitiesInput(
                saved.getId(),
                null,
                new ListingRepository.SaveLocationInput(
                    str(body, "city"),
                    str(body, "district"),
                    str(body, "municipality"),
                    str(body, "parish"),
                    str(body, "neighborhood"),
                    str(body, "street"),
                    null,
                    str(body, "postalCode"),
                    doubleOrNull(body, "latitude"),
                    doubleOrNull(body, "longitude"),
                    precision != null ? precision : "neighborhood",
                    false
                )
            ));
        }

        // ── Pricing sub-entity ────────────────────────────────────────────────
        boolean hasPricingFields = body.containsKey("condoFee") || body.containsKey("depositRequired")
            || body.containsKey("propertyTaxAnnual") || body.containsKey("maintenanceCostEstimate");

        if (hasPricingFields) {
            var bt = saved.getBusinessType();
            var pricePeriod = "rent".equals(bt) || "holiday_rent".equals(bt) ? "month" : "sale";
            repository.saveSubEntities(new ListingRepository.SaveSubEntitiesInput(
                saved.getId(),
                new ListingRepository.SavePricingInput(
                    saved.getPriceAmount(),
                    null,
                    pricePeriod,
                    decimal(body, "condoFee"),
                    decimal(body, "depositRequired"),
                    decimal(body, "propertyTaxAnnual"),
                    decimal(body, "maintenanceCostEstimate"),
                    false,
                    false
                ),
                null
            ));
        }

        // ── Features sub-entity (featureTags) ─────────────────────────────────
        if (body.containsKey("featureTags")) {
            var rawTags = body.get("featureTags");
            var tagsList = rawTags instanceof List<?> list ? list : List.of();
            try {
                var tagsJson = json.writeValueAsString(tagsList);
                jdbc.sql("""
                    INSERT INTO properia.listing_features
                      (listing_id, feature_tags, feature_flags, view_tags, lifestyle_tags, premium_signals, updated_at)
                    VALUES
                      (:lid, :tags::jsonb, '{}', '[]', '[]', '[]', now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      feature_tags = EXCLUDED.feature_tags,
                      updated_at = now()
                    """)
                    .param("lid", saved.getId())
                    .param("tags", tagsJson)
                    .update();
            } catch (Exception e) {
                // non-fatal: feature tags not critical
            }
        }

        // ── Energy sub-entity ─────────────────────────────────────────────────
        boolean hasEnergyFields = body.containsKey("energyCertificateNumber")
            || body.containsKey("energyCertificateValidUntil")
            || body.containsKey("energyCertificateExemptionReason");

        if (hasEnergyFields) {
            try {
                var certNumber   = str(body, "energyCertificateNumber");
                var certUntilStr = str(body, "energyCertificateValidUntil");
                var exemption    = str(body, "energyCertificateExemptionReason");
                var certStatus   = (exemption != null && !exemption.isBlank()) ? "exempt"
                    : (certNumber != null || certUntilStr != null) ? "declared"
                    : null;
                var certUntilDate = (certUntilStr != null && !certUntilStr.isBlank())
                    ? LocalDate.parse(certUntilStr.substring(0, 10)) : null;
                jdbc.sql("""
                    INSERT INTO properia.listing_energy
                      (listing_id, energy_certificate_rating, energy_certificate_number,
                       energy_certificate_valid_until, energy_certificate_exemption_reason,
                       energy_certificate_status, updated_at)
                    VALUES
                      (:lid, :rating, :certNumber, :validUntil, :exemption, :status, now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      energy_certificate_rating      = EXCLUDED.energy_certificate_rating,
                      energy_certificate_number      = EXCLUDED.energy_certificate_number,
                      energy_certificate_valid_until = EXCLUDED.energy_certificate_valid_until,
                      energy_certificate_exemption_reason = EXCLUDED.energy_certificate_exemption_reason,
                      energy_certificate_status      = EXCLUDED.energy_certificate_status,
                      updated_at = now()
                    """)
                    .param("lid",       saved.getId())
                    .param("rating",    saved.getEnergyRating())
                    .param("certNumber", certNumber)
                    .param("validUntil", certUntilDate)
                    .param("exemption",  exemption)
                    .param("status",     certStatus)
                    .update();
            } catch (Exception e) {
                // non-fatal
            }
        }

        // ── Commercial URLs sub-entity (youtube / virtual tour) ───────────────
        boolean hasCommercialUrlFields = body.containsKey("youtubeVideoUrl")
            || body.containsKey("virtualTourUrl");

        if (hasCommercialUrlFields) {
            try {
                jdbc.sql("""
                    INSERT INTO properia.listing_commercial
                      (listing_id, youtube_tour_url, virtual_tour_url, updated_at)
                    VALUES
                      (:lid, :youtube, :virtual, now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      youtube_tour_url = EXCLUDED.youtube_tour_url,
                      virtual_tour_url = EXCLUDED.virtual_tour_url,
                      updated_at = now()
                    """)
                    .param("lid",     saved.getId())
                    .param("youtube", str(body, "youtubeVideoUrl"))
                    .param("virtual", str(body, "virtualTourUrl"))
                    .update();
            } catch (Exception e) {
                // non-fatal
            }
        }

        // ── Room details sub-entity ───────────────────────────────────────────
        boolean hasRoomFields = body.containsKey("roomHasPrivateBathroom")
            || body.containsKey("roomBillsIncluded")
            || body.containsKey("roomInternetIncluded")
            || body.containsKey("roomHouseRulesText")
            || body.containsKey("roomMinStayMonths");

        if (hasRoomFields) {
            try {
                jdbc.sql("""
                    INSERT INTO properia.listing_room_details
                      (listing_id, has_private_bathroom, bills_included, internet_included,
                       has_shared_kitchen, total_rooms_in_house, current_occupants,
                       min_stay_months, couple_allowed, is_exterior_room, house_rules_text, updated_at)
                    VALUES
                      (:lid, :hasPrivateBathroom, :billsIncluded, :internetIncluded,
                       :hasSharedKitchen, :totalRoomsInHouse, :currentOccupants,
                       :minStayMonths, :coupleAllowed, :isExteriorRoom, :houseRulesText, now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      has_private_bathroom   = EXCLUDED.has_private_bathroom,
                      bills_included         = EXCLUDED.bills_included,
                      internet_included      = EXCLUDED.internet_included,
                      has_shared_kitchen     = EXCLUDED.has_shared_kitchen,
                      total_rooms_in_house   = EXCLUDED.total_rooms_in_house,
                      current_occupants      = EXCLUDED.current_occupants,
                      min_stay_months        = EXCLUDED.min_stay_months,
                      couple_allowed         = EXCLUDED.couple_allowed,
                      is_exterior_room       = EXCLUDED.is_exterior_room,
                      house_rules_text       = EXCLUDED.house_rules_text,
                      updated_at             = now()
                    """)
                    .param("lid",                saved.getId())
                    .param("hasPrivateBathroom", bool(body, "roomHasPrivateBathroom"))
                    .param("billsIncluded",      bool(body, "roomBillsIncluded"))
                    .param("internetIncluded",   bool(body, "roomInternetIncluded"))
                    .param("hasSharedKitchen",   bool(body, "roomHasSharedKitchen"))
                    .param("totalRoomsInHouse",  intOrNull(body, "roomTotalRoomsInHouse"))
                    .param("currentOccupants",   intOrNull(body, "roomCurrentOccupants"))
                    .param("minStayMonths",      intOrNull(body, "roomMinStayMonths"))
                    .param("coupleAllowed",      bool(body, "roomCoupleAllowed"))
                    .param("isExteriorRoom",     bool(body, "roomIsExteriorRoom"))
                    .param("houseRulesText",     str(body, "roomHouseRulesText"))
                    .update();
            } catch (Exception e) {
                // non-fatal
            }
        }

        // ── Commercial details sub-entity ─────────────────────────────────────
        boolean hasCommercialDetailFields = body.containsKey("commercialHasShopfront")
            || body.containsKey("commercialHasWc")
            || body.containsKey("commercialStreetVisibility")
            || body.containsKey("commercialPermittedUse")
            || body.containsKey("commercialInternalFloors");

        if (hasCommercialDetailFields) {
            try {
                var streetVis = str(body, "commercialStreetVisibility");
                jdbc.sql("""
                    INSERT INTO properia.listing_commercial_details
                      (listing_id, has_shopfront, street_visibility, internal_floors,
                       has_vehicle_access, permitted_use, has_flue_pipe, has_extraction_system,
                       has_wc, has_kitchenette, has_outdoor_seating_potential, updated_at)
                    VALUES
                      (:lid, :hasShopfront, :streetVisibility::properia.street_visibility, :internalFloors,
                       :hasVehicleAccess, :permittedUse, :hasFluePipe, :hasExtractionSystem,
                       :hasWc, :hasKitchenette, :hasOutdoorSeatingPotential, now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      has_shopfront                  = EXCLUDED.has_shopfront,
                      street_visibility              = EXCLUDED.street_visibility,
                      internal_floors                = EXCLUDED.internal_floors,
                      has_vehicle_access             = EXCLUDED.has_vehicle_access,
                      permitted_use                  = EXCLUDED.permitted_use,
                      has_flue_pipe                  = EXCLUDED.has_flue_pipe,
                      has_extraction_system          = EXCLUDED.has_extraction_system,
                      has_wc                         = EXCLUDED.has_wc,
                      has_kitchenette                = EXCLUDED.has_kitchenette,
                      has_outdoor_seating_potential  = EXCLUDED.has_outdoor_seating_potential,
                      updated_at                     = now()
                    """)
                    .param("lid",                       saved.getId())
                    .param("hasShopfront",              bool(body, "commercialHasShopfront"))
                    .param("streetVisibility",          streetVis)
                    .param("internalFloors",            intOrNull(body, "commercialInternalFloors"))
                    .param("hasVehicleAccess",          bool(body, "commercialHasVehicleAccess"))
                    .param("permittedUse",              str(body, "commercialPermittedUse"))
                    .param("hasFluePipe",               bool(body, "commercialHasFluePipe"))
                    .param("hasExtractionSystem",       bool(body, "commercialHasExtractionSystem"))
                    .param("hasWc",                     bool(body, "commercialHasWc"))
                    .param("hasKitchenette",            bool(body, "commercialHasKitchenette"))
                    .param("hasOutdoorSeatingPotential", bool(body, "commercialHasOutdoorSeatingPotential"))
                    .update();
            } catch (Exception e) {
                // non-fatal
            }
        }

        // ── Build response ─────────────────────────────────────────────────────
        var resp = new LinkedHashMap<String, Object>();
        resp.put("id", saved.getId().toString());
        resp.put("publicId", saved.getPublicId());
        resp.put("advertiserId", saved.getAdvertiserId().toString());
        resp.put("status", saved.getStatus());
        resp.put("assignedAgentId", saved.getOwnerUserId() != null ? saved.getOwnerUserId().toString() : null);
        resp.put("title", saved.getTitle());
        resp.put("businessType", saved.getBusinessType());
        resp.put("propertyType", saved.getPropertyType());
        resp.put("priceAmount", saved.getPriceAmount() != null ? saved.getPriceAmount().toPlainString() : null);
        resp.put("priceCurrency", saved.getPriceCurrency());
        resp.put("bedrooms", saved.getBedrooms());
        resp.put("bathrooms", saved.getBathrooms() != null ? saved.getBathrooms().toPlainString() : "0");
        resp.put("suites", saved.getSuites());
        resp.put("garageSpaces", saved.getGarageSpaces());
        resp.put("parkingSpaces", saved.getParkingSpaces());
        resp.put("usableAreaM2", saved.getUsableAreaM2() != null ? saved.getUsableAreaM2().toPlainString() : null);
        resp.put("grossAreaM2", saved.getGrossAreaM2() != null ? saved.getGrossAreaM2().toPlainString() : null);
        resp.put("lotAreaM2", saved.getLotAreaM2() != null ? saved.getLotAreaM2().toPlainString() : null);
        resp.put("floorNumber", saved.getFloorNumber());
        resp.put("totalFloors", saved.getTotalFloors());
        resp.put("constructionYear", saved.getConstructionYear());
        resp.put("renovationYear", saved.getRenovationYear());
        resp.put("energyRating", saved.getEnergyRating());
        resp.put("sunExposure", saved.getSunExposure());
        resp.put("city", saved.getCity());
        resp.put("district", saved.getDistrict());
        resp.put("parish", saved.getParish());
        resp.put("neighborhood", saved.getNeighborhood());
        resp.put("postalCode", saved.getPostalCode());
        resp.put("latitude", saved.getLatitude());
        resp.put("longitude", saved.getLongitude());
        resp.put("heroImageUrl", saved.getHeroImageUrl());
        resp.put("descriptionShort", saved.getDescriptionShort());
        resp.put("conditionStatus", saved.getConditionDeclared());
        resp.put("furnishedStatus", saved.getFurnishedDeclared());
        resp.put("isFeatured", saved.isFeatured());
        resp.put("isImmediatelyAvailable", saved.isImmediatelyAvailable());
        resp.put("visibilityStatus", saved.getVisibilityStatus());
        resp.put("publishedAt", saved.getPublishedAt() != null ? saved.getPublishedAt().toString() : null);
        resp.put("updatedAt", saved.getUpdatedAt() != null ? saved.getUpdatedAt().toString() : null);
        resp.put("dataEntryAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
        resp.put("availableFrom", saved.getAvailableFrom() != null ? saved.getAvailableFrom().toString() : null);
        resp.put("alRegistrationNumber", saved.getAlRegistrationNumber());
        // Sub-entity fields echoed from request body (saved to respective tables)
        resp.put("condoFee", body.get("condoFee"));
        resp.put("propertyTaxAnnual", body.get("propertyTaxAnnual"));
        resp.put("depositRequired", body.get("depositRequired"));
        resp.put("maintenanceCostEstimate", body.get("maintenanceCostEstimate"));
        resp.put("featureTags", body.containsKey("featureTags") ? body.get("featureTags") : List.of());
        resp.put("street", body.get("street"));
        resp.put("municipality", body.get("municipality"));
        resp.put("locationPrecision", body.get("locationPrecision"));
        resp.put("youtubeVideoUrl", body.get("youtubeVideoUrl"));
        resp.put("virtualTourUrl", body.get("virtualTourUrl"));
        resp.put("energyCertificateNumber", body.get("energyCertificateNumber"));
        resp.put("energyCertificateValidUntil", body.get("energyCertificateValidUntil"));
        resp.put("energyCertificateExemptionReason", body.get("energyCertificateExemptionReason"));
        // Room details
        if (body.containsKey("roomHasPrivateBathroom") || body.containsKey("roomBillsIncluded")) {
            var rd = new LinkedHashMap<String, Object>();
            rd.put("hasPrivateBathroom", body.get("roomHasPrivateBathroom"));
            rd.put("billsIncluded", body.get("roomBillsIncluded"));
            rd.put("internetIncluded", body.get("roomInternetIncluded"));
            rd.put("hasSharedKitchen", body.get("roomHasSharedKitchen"));
            rd.put("totalRoomsInHouse", body.get("roomTotalRoomsInHouse"));
            rd.put("currentOccupants", body.get("roomCurrentOccupants"));
            rd.put("minStayMonths", body.get("roomMinStayMonths"));
            rd.put("coupleAllowed", body.get("roomCoupleAllowed"));
            rd.put("isExteriorRoom", body.get("roomIsExteriorRoom"));
            rd.put("houseRulesText", body.get("roomHouseRulesText"));
            resp.put("roomDetails", rd);
        }
        // Commercial details
        if (body.containsKey("commercialHasShopfront") || body.containsKey("commercialHasWc")) {
            var cd = new LinkedHashMap<String, Object>();
            cd.put("hasShopfront", body.get("commercialHasShopfront"));
            cd.put("streetVisibility", body.get("commercialStreetVisibility"));
            cd.put("internalFloors", body.get("commercialInternalFloors"));
            cd.put("hasVehicleAccess", body.get("commercialHasVehicleAccess"));
            cd.put("permittedUse", body.get("commercialPermittedUse"));
            cd.put("hasFluePipe", body.get("commercialHasFluePipe"));
            cd.put("hasExtractionSystem", body.get("commercialHasExtractionSystem"));
            cd.put("hasWc", body.get("commercialHasWc"));
            cd.put("hasKitchenette", body.get("commercialHasKitchenette"));
            cd.put("hasOutdoorSeatingPotential", body.get("commercialHasOutdoorSeatingPotential"));
            resp.put("commercialDetails", cd);
        }

        return resp;
    }

    // ── Read full listing for edit ────────────────────────────────────────────

    public Map<String, Object> getForEdit(UUID listingId, UUID advertiserId) {
        var l = repository.findByIdAndAdvertiserId(listingId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        var resp = new LinkedHashMap<String, Object>();
        resp.put("id", l.getId().toString());
        resp.put("publicId", l.getPublicId());
        resp.put("advertiserId", l.getAdvertiserId().toString());
        resp.put("status", l.getStatus());
        resp.put("assignedAgentId", l.getOwnerUserId() != null ? l.getOwnerUserId().toString() : null);
        resp.put("title", l.getTitle());
        resp.put("businessType", l.getBusinessType());
        resp.put("propertyType", l.getPropertyType());
        resp.put("priceAmount", l.getPriceAmount() != null ? l.getPriceAmount().toPlainString() : null);
        resp.put("priceCurrency", l.getPriceCurrency());
        resp.put("bedrooms", l.getBedrooms());
        resp.put("bathrooms", l.getBathrooms() != null ? l.getBathrooms().toPlainString() : "0");
        resp.put("suites", l.getSuites());
        resp.put("garageSpaces", l.getGarageSpaces());
        resp.put("parkingSpaces", l.getParkingSpaces());
        resp.put("usableAreaM2", l.getUsableAreaM2() != null ? l.getUsableAreaM2().toPlainString() : null);
        resp.put("grossAreaM2", l.getGrossAreaM2() != null ? l.getGrossAreaM2().toPlainString() : null);
        resp.put("lotAreaM2", l.getLotAreaM2() != null ? l.getLotAreaM2().toPlainString() : null);
        resp.put("floorNumber", l.getFloorNumber());
        resp.put("totalFloors", l.getTotalFloors());
        resp.put("constructionYear", l.getConstructionYear());
        resp.put("renovationYear", l.getRenovationYear());
        resp.put("energyRating", l.getEnergyRating());
        resp.put("sunExposure", l.getSunExposure());
        resp.put("city", l.getCity());
        resp.put("district", l.getDistrict());
        resp.put("parish", l.getParish());
        resp.put("neighborhood", l.getNeighborhood());
        resp.put("postalCode", l.getPostalCode());
        resp.put("latitude", l.getLatitude());
        resp.put("longitude", l.getLongitude());
        resp.put("heroImageUrl", l.getHeroImageUrl());
        resp.put("descriptionRaw", l.getDescriptionRaw());
        resp.put("descriptionShort", l.getDescriptionShort());
        resp.put("conditionStatus", l.getConditionDeclared());
        resp.put("furnishedStatus", l.getFurnishedDeclared());
        resp.put("isFeatured", l.isFeatured());
        resp.put("isImmediatelyAvailable", l.isImmediatelyAvailable());
        resp.put("visibilityStatus", l.getVisibilityStatus());
        resp.put("publishedAt", l.getPublishedAt() != null ? l.getPublishedAt().toString() : null);
        resp.put("updatedAt", l.getUpdatedAt() != null ? l.getUpdatedAt().toString() : null);
        resp.put("dataEntryAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
        resp.put("availableFrom", l.getAvailableFrom() != null ? l.getAvailableFrom().toString() : null);
        resp.put("alRegistrationNumber", l.getAlRegistrationNumber());

        // Zone scores
        jdbc.sql("""
            SELECT zone_label_primary, zone_summary_short
            FROM properia.listing_zone_scores WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                resp.put("zoneLabelPrimary", rs.getString("zone_label_primary"));
                resp.put("zoneSummaryShort", rs.getString("zone_summary_short"));
                return null;
            }).optional().ifPresentOrElse(
                ignored -> {},
                () -> { resp.put("zoneLabelPrimary", null); resp.put("zoneSummaryShort", null); }
            );

        // Location sub-entity
        jdbc.sql("""
            SELECT COALESCE(street, '') AS street,
                   COALESCE(location_precision::text, 'neighborhood') AS location_precision,
                   COALESCE(municipality, '') AS municipality
            FROM properia.listing_location WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                resp.put("street", rs.getString("street"));
                resp.put("locationPrecision", rs.getString("location_precision"));
                resp.put("municipality", rs.getString("municipality"));
                return null;
            }).optional().ifPresentOrElse(
                ignored -> {},
                () -> { resp.put("street", null); resp.put("locationPrecision", null); resp.put("municipality", null); }
            );

        // Pricing sub-entity
        jdbc.sql("""
            SELECT condo_fee, deposit_required, property_tax_annual, maintenance_cost_estimate
            FROM properia.listing_pricing WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                var cf = rs.getBigDecimal("condo_fee");
                var dr = rs.getBigDecimal("deposit_required");
                var pt = rs.getBigDecimal("property_tax_annual");
                var mc = rs.getBigDecimal("maintenance_cost_estimate");
                resp.put("condoFee", cf != null ? cf.toPlainString() : null);
                resp.put("depositRequired", dr != null ? dr.toPlainString() : null);
                resp.put("propertyTaxAnnual", pt != null ? pt.toPlainString() : null);
                resp.put("maintenanceCostEstimate", mc != null ? mc.toPlainString() : null);
                return null;
            }).optional().ifPresentOrElse(
                ignored -> {},
                () -> {
                    resp.put("condoFee", null);
                    resp.put("depositRequired", null);
                    resp.put("propertyTaxAnnual", null);
                    resp.put("maintenanceCostEstimate", null);
                }
            );

        // Energy sub-entity
        jdbc.sql("""
            SELECT energy_certificate_number, energy_certificate_valid_until,
                   energy_certificate_exemption_reason
            FROM properia.listing_energy WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                var until = rs.getDate("energy_certificate_valid_until");
                resp.put("energyCertificateNumber", rs.getString("energy_certificate_number"));
                resp.put("energyCertificateValidUntil", until != null ? until.toLocalDate().toString() : null);
                resp.put("energyCertificateExemptionReason", rs.getString("energy_certificate_exemption_reason"));
                return null;
            }).optional().ifPresentOrElse(
                ignored -> {},
                () -> {
                    resp.put("energyCertificateNumber", null);
                    resp.put("energyCertificateValidUntil", null);
                    resp.put("energyCertificateExemptionReason", null);
                }
            );

        // Features sub-entity
        resp.put("featureTags", List.of());
        jdbc.sql("SELECT feature_tags FROM properia.listing_features WHERE listing_id = :id")
            .param("id", listingId)
            .query((rs, n) -> rs.getString("feature_tags"))
            .optional().ifPresent(raw -> {
                try { resp.put("featureTags", json.readValue(raw, List.class)); }
                catch (Exception ignored) {}
            });

        // Commercial sub-entity (YouTube / virtual tour)
        jdbc.sql("""
            SELECT youtube_tour_url, virtual_tour_url, virtual_tour_status
            FROM properia.listing_commercial WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                resp.put("youtubeVideoUrl", rs.getString("youtube_tour_url"));
                resp.put("virtualTourUrl", rs.getString("virtual_tour_url"));
                resp.put("virtualTourStatus", rs.getString("virtual_tour_status"));
                return null;
            }).optional().ifPresentOrElse(
                ignored -> {},
                () -> { resp.put("youtubeVideoUrl", null); resp.put("virtualTourUrl", null); resp.put("virtualTourStatus", null); }
            );

        // Room details sub-entity
        jdbc.sql("""
            SELECT has_private_bathroom, bills_included, internet_included, has_shared_kitchen,
                   total_rooms_in_house, current_occupants, min_stay_months, couple_allowed,
                   is_exterior_room, house_rules_text
            FROM properia.listing_room_details WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                var rd = new LinkedHashMap<String, Object>();
                rd.put("hasPrivateBathroom", rs.getBoolean("has_private_bathroom"));
                rd.put("billsIncluded", rs.getBoolean("bills_included"));
                rd.put("internetIncluded", rs.getBoolean("internet_included"));
                rd.put("hasSharedKitchen", rs.getBoolean("has_shared_kitchen"));
                rd.put("totalRoomsInHouse", rs.getObject("total_rooms_in_house"));
                rd.put("currentOccupants", rs.getObject("current_occupants"));
                rd.put("minStayMonths", rs.getObject("min_stay_months"));
                rd.put("coupleAllowed", rs.getBoolean("couple_allowed"));
                rd.put("isExteriorRoom", rs.getBoolean("is_exterior_room"));
                rd.put("houseRulesText", rs.getString("house_rules_text"));
                return rd;
            }).optional().ifPresent(rd -> resp.put("roomDetails", rd));

        // Commercial details sub-entity
        jdbc.sql("""
            SELECT has_shopfront, street_visibility::text, internal_floors, has_vehicle_access,
                   permitted_use, has_flue_pipe, has_extraction_system, has_wc, has_kitchenette,
                   has_outdoor_seating_potential
            FROM properia.listing_commercial_details WHERE listing_id = :id
            """).param("id", listingId)
            .query((rs, n) -> {
                var cd = new LinkedHashMap<String, Object>();
                cd.put("hasShopfront", rs.getBoolean("has_shopfront"));
                cd.put("streetVisibility", rs.getString("street_visibility"));
                cd.put("internalFloors", rs.getObject("internal_floors"));
                cd.put("hasVehicleAccess", rs.getBoolean("has_vehicle_access"));
                cd.put("permittedUse", rs.getString("permitted_use"));
                cd.put("hasFluePipe", rs.getBoolean("has_flue_pipe"));
                cd.put("hasExtractionSystem", rs.getBoolean("has_extraction_system"));
                cd.put("hasWc", rs.getBoolean("has_wc"));
                cd.put("hasKitchenette", rs.getBoolean("has_kitchenette"));
                cd.put("hasOutdoorSeatingPotential", rs.getBoolean("has_outdoor_seating_potential"));
                return cd;
            }).optional().ifPresent(cd -> resp.put("commercialDetails", cd));

        return resp;
    }

    // ── Zone helpers ──────────────────────────────────────────────────────────

    private Map<String, String> getLocationForZone(UUID listingId) {
        return jdbc.sql("""
            SELECT street, neighborhood, location_precision AS precision
            FROM properia.listing_location WHERE listing_id = :lid
            """)
            .param("lid", listingId)
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, String>();
                m.put("street",       rs.getString("street"));
                m.put("neighborhood", rs.getString("neighborhood"));
                m.put("precision",    rs.getString("precision"));
                return m;
            })
            .optional()
            .orElseGet(java.util.LinkedHashMap::new);
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private String str(Map<String, Object> m, String key) {
        var v = m.get(key);
        return v == null ? null : v.toString();
    }

    private int intVal(Map<String, Object> m, String key) {
        var v = m.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private Integer intOrNull(Map<String, Object> m, String key) {
        var v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        var s = v.toString();
        if (s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private boolean bool(Map<String, Object> m, String key) {
        var v = m.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private BigDecimal decimal(Map<String, Object> m, String key) {
        var v = m.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        var s = v.toString().trim();
        if (s.isBlank()) return null;
        try { return new BigDecimal(s.replace(",", ".")); } catch (Exception e) { return null; }
    }

    private Double doubleOrNull(Map<String, Object> m, String key) {
        var v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        var s = v.toString().trim();
        if (s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }
}
