package pt.properia.api.modules.listings.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional
public class CreateListingUseCase {

    private final ListingRepository repository;
    private final JdbcClient jdbc;

    public CreateListingUseCase(ListingRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    public record Command(
        UUID advertiserId,
        UUID ownerUserId,
        String businessType,
        String propertyType,
        String propertySubtype,
        String title,
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

    public Listing execute(Command cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "O título é obrigatório.");
        }
        if (cmd.businessType() == null) {
            throw new DomainException("VALIDATION_ERROR", "O tipo de negócio é obrigatório.");
        }
        if (cmd.propertyType() == null) {
            throw new DomainException("VALIDATION_ERROR", "O tipo de imóvel é obrigatório.");
        }

        var listing = new Listing();
        listing.setPublicId(generatePublicId());
        listing.setAdvertiserId(cmd.advertiserId());
        listing.setOwnerUserId(cmd.ownerUserId());
        listing.setBusinessType(cmd.businessType());
        listing.setPropertyType(cmd.propertyType());
        listing.setPropertySubtype(cmd.propertySubtype());
        listing.setTitle(cmd.title().strip());
        listing.setTitleNormalized(cmd.title().strip().toLowerCase());
        listing.setDescriptionRaw(cmd.descriptionRaw());
        listing.setDescriptionShort(cmd.descriptionShort());
        listing.setPriceAmount(cmd.priceAmount());
        listing.setBedrooms(cmd.bedrooms() != null ? cmd.bedrooms() : 0);
        listing.setBathrooms(cmd.bathrooms() != null ? cmd.bathrooms() : BigDecimal.ZERO);
        listing.setSuites(cmd.suites() != null ? cmd.suites() : 0);
        listing.setGarageSpaces(cmd.garageSpaces() != null ? cmd.garageSpaces() : 0);
        listing.setParkingSpaces(cmd.parkingSpaces() != null ? cmd.parkingSpaces() : 0);
        listing.setUsableAreaM2(cmd.usableAreaM2());
        listing.setGrossAreaM2(cmd.grossAreaM2());
        listing.setLotAreaM2(cmd.lotAreaM2());
        listing.setCity(cmd.city());
        listing.setDistrict(cmd.district());
        listing.setParish(cmd.parish());
        listing.setNeighborhood(cmd.neighborhood());
        listing.setPostalCode(cmd.postalCode());
        listing.setLatitude(cmd.latitude());
        listing.setLongitude(cmd.longitude());
        listing.setEnergyRating(cmd.energyRating());
        listing.setConditionDeclared(cmd.conditionDeclared());
        listing.setFurnishedDeclared(cmd.furnishedDeclared());
        listing.setAlRegistrationNumber(cmd.alRegistrationNumber());
        if (Boolean.TRUE.equals(cmd.isFeatured())) listing.setFeatured(true);
        listing.setStatus("draft");

        var saved = repository.save(listing);

        // ── Seed do histórico de preço ─────────────────────────────────────────
        // Regista o preço inicial como baseline para que o gráfico de evolução
        // apareça logo na 1ª alteração (e o "desceu %" use o preço original).
        if (cmd.priceAmount() != null) {
            jdbc.sql("""
                    INSERT INTO properia.listing_price_history
                      (listing_id, price_amount, price_currency)
                    VALUES (:lid, :price, :currency)
                    """)
                .param("lid", saved.getId())
                .param("price", cmd.priceAmount())
                .param("currency", saved.getPriceCurrency() != null ? saved.getPriceCurrency() : "EUR")
                .update();
        }

        // ── Location sub-entity ────────────────────────────────────────────────
        if (cmd.city() != null || cmd.street() != null || cmd.latitude() != null) {
            var precision = cmd.locationPrecision() != null ? cmd.locationPrecision() : "neighborhood";
            repository.saveSubEntities(new ListingRepository.SaveSubEntitiesInput(
                saved.getId(),
                null,
                new ListingRepository.SaveLocationInput(
                    cmd.city(), cmd.district(), cmd.municipality(),
                    cmd.parish(), cmd.neighborhood(), cmd.street(),
                    null, cmd.postalCode(),
                    cmd.latitude(), cmd.longitude(),
                    precision, false
                )
            ));
        }

        // ── Pricing sub-entity ─────────────────────────────────────────────────
        if (cmd.priceAmount() != null) {
            var bt = cmd.businessType();
            var pricePeriod = "rent".equals(bt) || "holiday_rent".equals(bt) ? "month" : "sale";
            repository.saveSubEntities(new ListingRepository.SaveSubEntitiesInput(
                saved.getId(),
                new ListingRepository.SavePricingInput(
                    cmd.priceAmount(), null, pricePeriod,
                    null, null, null, null, false, false
                ),
                null
            ));
        }

        // ── Energy sub-entity ──────────────────────────────────────────────────
        if (cmd.energyCertificateNumber() != null || cmd.energyCertificateValidUntil() != null
                || cmd.energyCertificateExemptionReason() != null || cmd.energyRating() != null) {
            try {
                var exemption = cmd.energyCertificateExemptionReason();
                var certStatus = (exemption != null && !exemption.isBlank()) ? "exempt"
                    : (cmd.energyCertificateNumber() != null || cmd.energyCertificateValidUntil() != null) ? "declared"
                    : null;
                var validUntilStr = cmd.energyCertificateValidUntil();
                var validUntilDate = (validUntilStr != null && !validUntilStr.isBlank())
                    ? LocalDate.parse(validUntilStr.substring(0, 10)) : null;
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
                    .param("lid",        saved.getId())
                    .param("rating",     cmd.energyRating())
                    .param("certNumber", cmd.energyCertificateNumber())
                    .param("validUntil", validUntilDate)
                    .param("exemption",  exemption)
                    .param("status",     certStatus)
                    .update();
            } catch (Exception ignored) {}
        }

        // ── Commercial URLs sub-entity ─────────────────────────────────────────
        if (cmd.youtubeVideoUrl() != null) {
            try {
                jdbc.sql("""
                    INSERT INTO properia.listing_commercial
                      (listing_id, youtube_tour_url, updated_at)
                    VALUES (:lid, :youtube, now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      youtube_tour_url = EXCLUDED.youtube_tour_url,
                      updated_at = now()
                    """)
                    .param("lid",     saved.getId())
                    .param("youtube", cmd.youtubeVideoUrl())
                    .update();
            } catch (Exception ignored) {}
        }

        return saved;
    }

    private String generatePublicId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
