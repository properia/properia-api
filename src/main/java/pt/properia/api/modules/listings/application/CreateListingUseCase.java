package pt.properia.api.modules.listings.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CreateListingUseCase {

    private static final Set<String> ROLES_ALLOWED_TO_REASSIGN = Set.of("owner", "admin");
    private static final Set<String> ROLES_ELIGIBLE_AS_ASSIGNEE = Set.of("owner", "admin", "sales");

    private final ListingRepository repository;
    private final JdbcClient jdbc;

    private final SpecialConditionClassifier specialConditions;

    public CreateListingUseCase(ListingRepository repository, JdbcClient jdbc,
                                SpecialConditionClassifier specialConditions) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.specialConditions = specialConditions;
    }

    public record Command(
        UUID advertiserId,
        UUID requestorUserId,
        UUID assignedAgentId,
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
        String landType,
        BigDecimal ceilingHeightM,
        String waterSource,
        Boolean agriculturalUse,
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
        String licencaUtilizacao,
        java.math.BigDecimal terraceAreaM2,
        java.math.BigDecimal gardenAreaM2,
        String heatingType,
        String coolingType,
        String waterHeatingType,
        Integer wcServico,
        String tipoCaixilharia,
        String localizacaoEdificio,
        Boolean seguroCondominioIncluido,
        Boolean exclusiveListing,
        Boolean fibraOtica,
        Boolean gasCanalizado,
        Boolean tvCabo,
        Boolean fossaSeptica,
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
        listing.setOwnerUserId(resolveOwnerUserId(cmd));
        listing.setBusinessType(cmd.businessType());
        listing.setPropertyType(cmd.propertyType());
        listing.setPropertySubtype(cmd.propertySubtype());
        listing.setTitle(cmd.title().strip());
        listing.setTitleNormalized(cmd.title().strip().toLowerCase());
        listing.setDescriptionRaw(cmd.descriptionRaw());
        listing.setDescriptionShort(cmd.descriptionShort());
        applySpecialConditions(listing);
        listing.setPriceAmount(cmd.priceAmount());
        listing.setBedrooms(cmd.bedrooms() != null ? cmd.bedrooms() : 0);
        listing.setBathrooms(cmd.bathrooms() != null ? cmd.bathrooms() : BigDecimal.ZERO);
        listing.setSuites(cmd.suites() != null ? cmd.suites() : 0);
        listing.setGarageSpaces(cmd.garageSpaces() != null ? cmd.garageSpaces() : 0);
        listing.setParkingSpaces(cmd.parkingSpaces() != null ? cmd.parkingSpaces() : 0);
        listing.setUsableAreaM2(cmd.usableAreaM2());
        listing.setGrossAreaM2(cmd.grossAreaM2());
        listing.setLotAreaM2(cmd.lotAreaM2());
        listing.setLandType(cmd.landType());
        listing.setCeilingHeightM(cmd.ceilingHeightM());
        listing.setWaterSource(cmd.waterSource());
        listing.setAgriculturalUse(cmd.agriculturalUse());
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
        listing.setLicencaUtilizacao(cmd.licencaUtilizacao());
        listing.setTerraceAreaM2(cmd.terraceAreaM2());
        listing.setGardenAreaM2(cmd.gardenAreaM2());
        listing.setHeatingType(cmd.heatingType());
        listing.setCoolingType(cmd.coolingType());
        listing.setWaterHeatingType(cmd.waterHeatingType());
        listing.setWcServico(cmd.wcServico());
        listing.setTipoCaixilharia(cmd.tipoCaixilharia());
        listing.setLocalizacaoEdificio(cmd.localizacaoEdificio());
        listing.setSeguroCondominioIncluido(cmd.seguroCondominioIncluido());
        if (Boolean.TRUE.equals(cmd.exclusiveListing())) listing.setExclusiveListing(true);
        if (Boolean.TRUE.equals(cmd.fibraOtica())) listing.setFibraOtica(true);
        if (Boolean.TRUE.equals(cmd.gasCanalizado())) listing.setGasCanalizado(true);
        if (Boolean.TRUE.equals(cmd.tvCabo())) listing.setTvCabo(true);
        if (Boolean.TRUE.equals(cmd.fossaSeptica())) listing.setFossaSeptica(true);
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

    // Espelha a regra de RBAC de PatchListingService.patch() (bloco "Agent assignment") para
    // que criar-e-atribuir-a-outro-consultor num único pedido siga a mesma política que a
    // reatribuição pós-criação — em vez de o campo ser silenciosamente ignorado.
    /**
     * Classifica condições especiais (nua propriedade, quota parte, exploração
     * turística) a partir do texto do anúncio. Corre em criação e em cada edição de
     * texto: corrigir a descrição tem de poder corrigir também a classificação, nos
     * dois sentidos — um anúncio que deixa de mencionar usufruto volta a FULL.
     */
    private void applySpecialConditions(Listing listing) {
        var result = specialConditions.classify(
            listing.getTitle(), listing.getDescriptionRaw(), listing.getDescriptionShort());
        listing.setOwnershipType(result.ownershipType());
        listing.setUsageRestriction(result.usageRestriction());
        listing.setSpecialConditionSummary(result.summary());
    }

    private UUID resolveOwnerUserId(Command cmd) {
        if (cmd.assignedAgentId() == null || cmd.assignedAgentId().equals(cmd.requestorUserId())) {
            return cmd.requestorUserId();
        }

        var requestorRole = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", cmd.advertiserId()).param("uid", cmd.requestorUserId())
            .query(String.class).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem permissão para atribuir este anúncio.", 403));
        if (!ROLES_ALLOWED_TO_REASSIGN.contains(requestorRole)) {
            throw new DomainException("FORBIDDEN", "Apenas owner ou admin podem atribuir o anúncio a outro consultor.", 403);
        }

        var assigneeRole = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", cmd.advertiserId()).param("uid", cmd.assignedAgentId())
            .query(String.class).optional()
            .orElseThrow(() -> new DomainException("VALIDATION_ERROR", "O consultor indicado não pertence a esta agência.", 400));
        if (!ROLES_ELIGIBLE_AS_ASSIGNEE.contains(assigneeRole)) {
            throw new DomainException("VALIDATION_ERROR", "O consultor indicado não é elegível para ser responsável por anúncios.", 400);
        }

        return cmd.assignedAgentId();
    }

    private String generatePublicId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
