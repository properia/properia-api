package pt.properia.api.modules.search.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.search.application.SearchRepository;
import pt.properia.api.modules.search.application.dto.ListingSearchItemDto;
import pt.properia.api.modules.search.application.dto.PriceHistorySnapshotDto;
import pt.properia.api.modules.search.application.dto.SearchParams;
import pt.properia.api.modules.search.application.dto.SearchRankingSummaryDto;
import pt.properia.api.modules.search.application.dto.SearchResultDto;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcSearchRepository implements SearchRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcSearchRepository.class);

    // ── POI: mapeamento das categorias do parser NL/IA para listing_poi_snapshots ──
    // 10 categorias têm distância exata (nearest_X_m); praia/cultura/biblioteca só
    // têm contagem dentro do raio do snapshot (700m) — usamos "presente" como proxy.
    private static final java.util.Map<String, String> POI_DISTANCE_COLUMN = java.util.Map.ofEntries(
        java.util.Map.entry("transporte", "nearest_transport_m"),
        java.util.Map.entry("escola", "nearest_school_m"),
        java.util.Map.entry("supermercado", "nearest_supermarket_m"),
        java.util.Map.entry("saude", "nearest_health_m"),
        java.util.Map.entry("parque", "nearest_park_m"),
        java.util.Map.entry("ginasio", "nearest_gym_m"),
        java.util.Map.entry("restaurante", "nearest_restaurant_m"),
        java.util.Map.entry("cafe", "nearest_cafe_m"),
        java.util.Map.entry("farmacia", "nearest_pharmacy_m"),
        java.util.Map.entry("banco", "nearest_bank_m")
    );
    private static final java.util.Map<String, String> POI_COUNT_COLUMN = java.util.Map.ofEntries(
        java.util.Map.entry("praia", "beach_count"),
        java.util.Map.entry("cultura", "culture_count"),
        java.util.Map.entry("biblioteca", "culture_count")
    );
    // Ritmo de caminhada urbano de referência (~4.8 km/h) para converter minutos → metros.
    private static final int WALK_METERS_PER_MINUTE = 80;
    private static final int DEFAULT_ZONE_MAX_MINUTES = 10;

    private final JdbcClient jdbc;

    public JdbcSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SearchResultDto search(SearchParams params) {
        var where = buildWhere(params);
        var sortPlan = buildSortPlan(params);
        int offset = (params.page() - 1) * params.pageSize();

        // Phase 1: lightweight query — only the listings table, no joins, no laterals,
        // EXCEPT when the chosen sort needs one (ex.: "value" junta a CTE de comparáveis).
        // Isso mantém o caminho rápido (sorts simples) exatamente como era.
        var idSql = (sortPlan.cte().isEmpty() ? "" : "WITH " + sortPlan.cte() + " ")
            + "SELECT l.id::text AS id FROM properia.listings l "
            + (sortPlan.fromExtra().isEmpty() ? "" : sortPlan.fromExtra() + " ")
            + where.sql()
            + " ORDER BY " + sortPlan.orderBy()
            + " LIMIT :limit OFFSET :offset";

        var idQuery = jdbc.sql(idSql);
        for (var e : where.params().entrySet()) {
            idQuery = idQuery.param(e.getKey(), e.getValue());
        }
        for (var e : sortPlan.params().entrySet()) {
            idQuery = idQuery.param(e.getKey(), e.getValue());
        }
        idQuery = idQuery.param("limit", params.pageSize()).param("offset", offset);

        long t0 = System.currentTimeMillis();
        var ids = idQuery.query(String.class).list();
        long t1 = System.currentTimeMillis();

        if (ids.isEmpty()) {
            long total = count(params);
            long t2 = System.currentTimeMillis();
            log.info("search sort={} size={} → idQuery={}ms count={}ms items=0",
                params.sort(), params.pageSize(), (t1 - t0), (t2 - t1));
            int totalPages = (int) Math.ceil((double) total / params.pageSize());
            return new SearchResultDto(List.of(), total, params.page(), params.pageSize(), totalPages,
                buildRankingSummary(params, sortPlan));
        }

        // Phase 2: full detail query for the specific page rows only.
        // IDs are embedded as literals because they come from the DB (safe, no SQL injection risk).
        // This ensures laterals run at most pageSize times regardless of table size.
        var idLiterals = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));
        var detailCtePrefix = sortPlan.cte().isEmpty() ? "" : "WITH " + sortPlan.cte() + " ";
        var detailSql = detailCtePrefix + """
            SELECT
              l.id, l.public_id, l.advertiser_id, l.title,
              l.business_type, l.property_type, l.status,
              l.visibility_status, l.is_featured,
              l.price_amount, l.price_currency,
              p.condo_fee, p.property_tax_annual, p.municipal_tax_estimate,
              p.deposit_required,
              l.bedrooms, l.bathrooms, l.suites,
              l.garage_spaces, l.parking_spaces,
              l.usable_area_m2, l.gross_area_m2, l.lot_area_m2,
              l.city, l.district, l.parish, l.neighborhood,
              loc.street, l.postal_code,
              loc.location_precision,
              COALESCE(l.latitude, loc.latitude) AS latitude,
              COALESCE(l.longitude, loc.longitude) AS longitude,
              COALESCE(l.hero_image_url, lm.cover_url) AS hero_image_url,
              lm.image_urls_arr,
              l.description_short,
              l.energy_rating,
              l.condition_final, l.furnished_final,
              l.has_garage, l.has_private_parking,
              l.has_balcony, l.has_terrace, l.has_garden,
              l.has_pool, l.has_elevator, l.has_natural_light,
              l.has_equipped_kitchen, l.has_built_in_closets,
              l.has_double_glazing, l.has_solar_panels,
              l.has_barbecue, l.has_laundry_area,
              l.pool_type,
              lf.feature_tags,
              l.floor_number, l.total_floors,
              l.construction_year, l.renovation_year,
              l.sun_exposure,
              l.is_immediately_available, l.available_from,
              l.published_at, l.updated_at,
              com.floorplan_url, com.youtube_tour_url, com.virtual_tour_url, com.virtual_tour_status,
              zs.zone_label_primary, zs.zone_summary_short,
              COALESCE(dv_agg.view_count, 0)      AS detail_views_total,
              COALESCE(ph_agg.change_count, 0)     AS ph_change_count,
              ph_agg.first_price                   AS ph_first_price,
              ph_agg.last_change_at                AS ph_last_change_at
            FROM properia.listings l
            LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id
            LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id
            LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id
            LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id
            LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id
            LEFT JOIN LATERAL (
                SELECT
                    (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url,
                    ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr
                FROM (
                    SELECT url, is_cover, sort_order
                    FROM properia.listing_media
                    WHERE listing_id = l.id AND media_type::text = 'image'
                    ORDER BY is_cover DESC, sort_order ASC
                    LIMIT 5
                ) top_media
            ) lm ON true
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS view_count
                FROM properia.listing_detail_views
                WHERE listing_id = l.id
            ) dv_agg ON true
            LEFT JOIN LATERAL (
                SELECT
                    COUNT(*)::int                                                              AS change_count,
                    (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1]                     AS first_price,
                    MAX(recorded_at)                                                           AS last_change_at
                FROM properia.listing_price_history
                WHERE listing_id = l.id
            ) ph_agg ON true
            """
            + (sortPlan.fromExtra().isEmpty() ? "" : "\n" + sortPlan.fromExtra() + "\n")
            + "WHERE l.id::text IN (" + idLiterals + ")\nORDER BY " + sortPlan.orderBy();

        long t2 = System.currentTimeMillis();
        var detailQuery = jdbc.sql(detailSql);
        for (var e : sortPlan.params().entrySet()) {
            detailQuery = detailQuery.param(e.getKey(), e.getValue());
        }
        var items = detailQuery.query((rs, rowNum) -> mapRow(rs)).list();
        long t3 = System.currentTimeMillis();

        long total = count(params);
        long t4 = System.currentTimeMillis();

        log.info("search sort={} size={} → idQuery={}ms detail={}ms count={}ms total={}ms items={}",
            params.sort(), params.pageSize(), (t1 - t0), (t3 - t2), (t4 - t3), (t4 - t0), items.size());

        int totalPages = (int) Math.ceil((double) total / params.pageSize());
        return new SearchResultDto(items, total, params.page(), params.pageSize(), totalPages,
            buildRankingSummary(params, sortPlan));
    }

    // ── Ranking summary — explica ao utilizador porque esta é a ordem ──────────
    // Só "semantic" quando o sort realmente usou dados reais (POI suave/comparáveis
    // de preço); sorts simples (preço/área/recente) ficam em "default" — nada a
    // explicar, seria ruído.

    private static final java.util.Map<String, String> POI_LABEL_PT = java.util.Map.ofEntries(
        java.util.Map.entry("transporte", "transportes"), java.util.Map.entry("escola", "escolas"),
        java.util.Map.entry("supermercado", "supermercado"), java.util.Map.entry("saude", "saúde"),
        java.util.Map.entry("parque", "parques"), java.util.Map.entry("ginasio", "ginásio"),
        java.util.Map.entry("restaurante", "restaurantes"), java.util.Map.entry("cafe", "cafés"),
        java.util.Map.entry("farmacia", "farmácia"), java.util.Map.entry("banco", "banco"),
        java.util.Map.entry("praia", "praia"), java.util.Map.entry("cultura", "cultura"),
        java.util.Map.entry("biblioteca", "biblioteca")
    );

    private SearchRankingSummaryDto buildRankingSummary(SearchParams p, SortPlan plan) {
        var sort = p.sort() == null ? "recente" : p.sort();

        if ("value".equals(sort)) {
            return new SearchRankingSummaryDto(
                "semantic",
                "Ordenado pela melhor relação preço/m²",
                "Comparámos o preço por m² de cada imóvel com outros publicados do mesmo tipo, negócio e cidade — os com melhor relação aparecem primeiro.",
                List.of("Preço/m²", "Comparáveis na mesma cidade"),
                "Estimativa a partir de anúncios publicados na Properia. Não substitui uma avaliação profissional."
            );
        }

        if ("score".equals(sort) && p.softPois() != null && !p.softPois().isEmpty()) {
            var labels = p.softPois().stream()
                .map(id -> POI_LABEL_PT.getOrDefault(id, id))
                .distinct()
                .toList();
            if (!labels.isEmpty()) {
                var labelsText = String.join(", ", labels);
                return new SearchRankingSummaryDto(
                    "semantic",
                    "Ordenado pela proximidade real ao que procuraste",
                    "Os imóveis mais próximos de " + labelsText + " aparecem primeiro, com base na distância real até esses locais.",
                    labels.stream().map(l -> l.substring(0, 1).toUpperCase() + l.substring(1)).toList(),
                    "Distâncias estimadas a partir de dados abertos (OpenStreetMap) — podem não refletir alterações recentes."
                );
            }
        }

        return SearchRankingSummaryDto.defaultMode();
    }

    @Override
    public long count(SearchParams params) {
        var where = buildWhere(params);
        var sql = "SELECT COUNT(*) FROM properia.listings l " + where.sql();

        var query = jdbc.sql(sql);
        for (var e : where.params().entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query.query(Long.class).single();
    }

    // ── WHERE clause builder ───────────────────────────────────────────────────

    private WhereClause buildWhere(SearchParams p) {
        var parts = new ArrayList<String>();
        var params = new java.util.LinkedHashMap<String, Object>();

        // Always: only published listings
        parts.add("l.status = 'published'");

        // Business type
        if (p.negocio() != null && !p.negocio().isBlank() && !"todos".equals(p.negocio())) {
            parts.add("l.business_type::text = :businessType");
            params.put("businessType", mapBusinessType(p.negocio()));
        }

        // Property types (multi-select, comma-separated)
        if (p.tipo() != null && !p.tipo().isEmpty()) {
            var types = p.tipo().stream()
                .map(this::mapPropertyType)
                .filter(t -> t != null && !t.isBlank())
                .toList();
            if (!types.isEmpty()) {
                parts.add("l.property_type::text = ANY(:propertyTypes)");
                params.put("propertyTypes", types.toArray(String[]::new));
            }
        }

        // Bedrooms
        if (p.quartos() != null && !p.quartos().isEmpty()) {
            // Treat largest value as "4+" (>= 4)
            boolean hasPlus = p.quartos().contains(4) || p.quartos().stream().anyMatch(q -> q >= 4);
            var exact = p.quartos().stream().filter(q -> q < 4).toList();
            if (hasPlus && !exact.isEmpty()) {
                parts.add("(l.bedrooms = ANY(:quartos) OR l.bedrooms >= 4)");
                params.put("quartos", exact.stream().mapToInt(Integer::intValue).toArray());
            } else if (hasPlus) {
                parts.add("l.bedrooms >= 4");
            } else {
                parts.add("l.bedrooms = ANY(:quartos)");
                params.put("quartos", exact.stream().mapToInt(Integer::intValue).toArray());
            }
        }

        // Price range
        if (p.precoMin() != null) {
            parts.add("l.price_amount >= :precoMin");
            params.put("precoMin", p.precoMin());
        }
        if (p.precoMax() != null) {
            parts.add("l.price_amount <= :precoMax");
            params.put("precoMax", p.precoMax());
        }

        // Area range
        if (p.areaMin() != null) {
            parts.add("l.usable_area_m2 >= :areaMin");
            params.put("areaMin", p.areaMin());
        }
        if (p.areaMax() != null) {
            parts.add("l.usable_area_m2 <= :areaMax");
            params.put("areaMax", p.areaMax());
        }

        // Bathrooms min
        if (p.bathroomMin() != null) {
            parts.add("l.bathrooms >= :bathroomMin");
            params.put("bathroomMin", p.bathroomMin());
        }

        // Floor min
        if (p.floorMin() != null) {
            parts.add("l.floor_number >= :floorMin");
            params.put("floorMin", p.floorMin());
        }

        // Energy rating (certificado)
        if (p.certificado() != null && !p.certificado().isEmpty()) {
            parts.add("l.energy_rating = ANY(:certificado)");
            params.put("certificado", p.certificado().toArray(String[]::new));
        }

        // Furnished status (mobilia)
        if (p.mobilia() != null && !p.mobilia().isEmpty()) {
            var mapped = p.mobilia().stream().map(this::mapMobilia).filter(m -> m != null).toList();
            if (!mapped.isEmpty()) {
                parts.add("l.furnished_final::text = ANY(:mobilia)");
                params.put("mobilia", mapped.toArray(String[]::new));
            }
        }

        // Condition status
        if (p.conditionStatus() != null && !p.conditionStatus().isEmpty()) {
            parts.add("l.condition_final::text = ANY(:conditionStatus)");
            params.put("conditionStatus", p.conditionStatus().toArray(String[]::new));
        }

        // Sun exposure
        if (p.sunExposure() != null && !p.sunExposure().isEmpty()) {
            parts.add("l.sun_exposure = ANY(:sunExposure)");
            params.put("sunExposure", p.sunExposure().toArray(String[]::new));
        }

        // Availability
        if ("imediata".equals(p.disponibilidade())) {
            parts.add("l.is_immediately_available = true");
        }

        // Feature flags (has_elevator, has_pool, etc.)
        if (p.features() != null) {
            for (var feature : p.features()) {
                if ("pet_friendly".equals(feature)) {
                    // Stored in listing_features.feature_flags JSONB, not a boolean column
                    parts.add("EXISTS (SELECT 1 FROM properia.listing_features lf2 WHERE lf2.listing_id = l.id AND lf2.feature_flags::jsonb->>'pet_friendly' = 'true')");
                } else {
                    var col = mapFeatureToColumn(feature);
                    if (col != null) {
                        parts.add("l." + col + " = true");
                    }
                }
            }
        }

        // Excluded feature flags ("sem varanda") — inverso do bloco acima.
        // IS NOT TRUE cobre FALSE e NULL (imóveis sem o dado deixam de ser excluídos por engano).
        if (p.excludeFeatures() != null) {
            for (var feature : p.excludeFeatures()) {
                if ("pet_friendly".equals(feature)) {
                    parts.add("NOT EXISTS (SELECT 1 FROM properia.listing_features lf3 WHERE lf3.listing_id = l.id AND lf3.feature_flags::jsonb->>'pet_friendly' = 'true')");
                } else {
                    var col = mapFeatureToColumn(feature);
                    if (col != null) {
                        parts.add("l." + col + " IS NOT TRUE");
                    }
                }
            }
        }

        // Estilo arquitetónico — via Vision AI (listing_ai_vision). Sem coluna nova.
        // Match no estilo principal OU em qualquer estilo detetado. Normaliza mediterranico→mediterraneo.
        if (p.estilos() != null && !p.estilos().isEmpty()) {
            var norm = p.estilos().stream()
                .map(s -> "mediterranico".equals(s) ? "mediterraneo" : s)
                .toArray(String[]::new);
            parts.add("EXISTS (SELECT 1 FROM properia.listing_ai_vision v WHERE v.listing_id = l.id "
                + "AND (v.style_primary = ANY(:estilos) "
                + "OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(v.styles_detected) sd WHERE sd = ANY(:estilos))))");
            params.put("estilos", norm);
        }

        // Terreno — classe de solo/uso (#2).
        if (p.landTypes() != null && !p.landTypes().isEmpty()) {
            parts.add("l.land_type = ANY(:landTypes)");
            params.put("landTypes", p.landTypes().toArray(String[]::new));
        }

        // Room-specific filters (join only when needed)
        boolean needsRoomJoin = p.roomHasPrivateBathroom() || p.roomBillsIncluded()
            || p.roomInternetIncluded() || p.roomCoupleAllowed() || p.roomIsExterior()
            || p.roomMinStayMonths() != null;

        if (needsRoomJoin) {
            // Add join hint via WHERE correlated exists
            if (p.roomHasPrivateBathroom()) parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.has_private_bathroom = true)");
            if (p.roomBillsIncluded()) parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.bills_included = true)");
            if (p.roomInternetIncluded()) parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.internet_included = true)");
            if (p.roomCoupleAllowed()) parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.couple_allowed = true)");
            if (p.roomIsExterior()) parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.is_exterior_room = true)");
            if (p.roomMinStayMonths() != null) {
                parts.add("EXISTS (SELECT 1 FROM properia.listing_room_details rd WHERE rd.listing_id = l.id AND rd.min_stay_months <= :roomMinStayMonths)");
                params.put("roomMinStayMonths", p.roomMinStayMonths());
            }
        }

        // Commercial-specific filters
        if (p.commercialHasShopfront()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_shopfront = true)");
        if (p.commercialHasVehicleAccess()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_vehicle_access = true)");
        if (p.commercialHasFluePipe()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_flue_pipe = true)");
        if (p.commercialHasExtractionSystem()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_extraction_system = true)");
        if (p.commercialStreetVisibility() != null && !p.commercialStreetVisibility().isEmpty()) {
            parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.street_visibility::text = ANY(:commercialStreetVisibility))");
            params.put("commercialStreetVisibility", p.commercialStreetVisibility().toArray(String[]::new));
        }
        if (p.commercialPermittedUse() != null && !p.commercialPermittedUse().isEmpty()) {
            parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.permitted_use = ANY(:commercialPermittedUse))");
            params.put("commercialPermittedUse", p.commercialPermittedUse().toArray(String[]::new));
        }

        // ── Filtros avançados (formulário de criar imóvel) ──────────────────────
        var adv = p.advanced();
        if (adv != null) {
            // Infraestruturas
            if (adv.fibraOtica()) parts.add("l.fibra_otica = true");
            if (adv.gasCanalizado()) parts.add("l.gas_canalizado = true");
            if (adv.tvCabo()) parts.add("l.tv_cabo = true");
            if (adv.fossaSeptica()) parts.add("l.fossa_septica = true");
            if (adv.seguroCondominioIncluido()) parts.add("l.seguro_condominio_incluido = true");
            // Sistemas & eficiência
            if (adv.heatingTypes() != null && !adv.heatingTypes().isEmpty()) {
                parts.add("l.heating_type = ANY(:heatingTypes)");
                params.put("heatingTypes", adv.heatingTypes().toArray(String[]::new));
            }
            if (adv.hasAirConditioning()) parts.add("l.cooling_type IS NOT NULL AND l.cooling_type NOT IN ('', 'none')");
            if (adv.waterHeatingTypes() != null && !adv.waterHeatingTypes().isEmpty()) {
                parts.add("l.water_heating_type = ANY(:waterHeatingTypes)");
                params.put("waterHeatingTypes", adv.waterHeatingTypes().toArray(String[]::new));
            }
            if (adv.vidrosDuplos()) parts.add("l.tipo_caixilharia IN ('pvc_duplo', 'aluminio_termico')");
            if (adv.buildingPositions() != null && !adv.buildingPositions().isEmpty()) {
                parts.add("l.localizacao_edificio = ANY(:buildingPositions)");
                params.put("buildingPositions", adv.buildingPositions().toArray(String[]::new));
            }
            // Divisões
            if (adv.suitesMin() != null) {
                parts.add("l.suites >= :suitesMin");
                params.put("suitesMin", adv.suitesMin());
            }
            if (adv.hasWcServico()) parts.add("l.wc_servico >= 1");
            // Custos
            if (adv.condoFeeMax() != null) {
                parts.add("l.condo_fee <= :condoFeeMax");
                params.put("condoFeeMax", adv.condoFeeMax());
            }
            if (adv.propertyTaxMax() != null) {
                parts.add("l.property_tax_annual <= :propertyTaxMax");
                params.put("propertyTaxMax", adv.propertyTaxMax());
            }
            if (adv.depositMax() != null) {
                parts.add("l.deposit_required <= :depositMax");
                params.put("depositMax", adv.depositMax());
            }
            // Construção
            if (adv.constructionYearMin() != null) {
                parts.add("l.construction_year >= :constructionYearMin");
                params.put("constructionYearMin", adv.constructionYearMin());
            }
            // Comercial (sub-tabela)
            if (adv.commercialHasWc()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_wc = true)");
            if (adv.commercialHasKitchenette()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_kitchenette = true)");
            if (adv.commercialHasOutdoorSeating()) parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.has_outdoor_seating_potential = true)");
            if (adv.commercialInternalFloorsMin() != null) {
                parts.add("EXISTS (SELECT 1 FROM properia.listing_commercial_details cd WHERE cd.listing_id = l.id AND cd.internal_floors >= :commercialInternalFloorsMin)");
                params.put("commercialInternalFloorsMin", adv.commercialInternalFloorsMin());
            }
            // Rural / terreno
            if (adv.waterSources() != null && !adv.waterSources().isEmpty()) {
                parts.add("l.water_source = ANY(:waterSources)");
                params.put("waterSources", adv.waterSources().toArray(String[]::new));
            }
            if (adv.agriculturalUse()) parts.add("l.agricultural_use = true");
        }

        // Pontos de interesse rígidos ("perto de metro", etc.) — antes disto, o
        // parser detetava a intenção mas o backend ignorava-a por completo (só
        // afetava chips visuais). Usa a distância real do snapshot mais recente.
        if (p.hardPois() != null && !p.hardPois().isEmpty()) {
            int maxMeters = (p.zoneMaxMinutes() != null ? p.zoneMaxMinutes() : DEFAULT_ZONE_MAX_MINUTES)
                * WALK_METERS_PER_MINUTE;
            var conditions = new ArrayList<String>();
            for (var poi : p.hardPois()) {
                var distCol = POI_DISTANCE_COLUMN.get(poi);
                var countCol = POI_COUNT_COLUMN.get(poi);
                if (distCol != null) {
                    conditions.add("EXISTS (SELECT 1 FROM properia.listing_poi_snapshots ps "
                        + "WHERE ps.listing_id = l.id AND ps." + distCol + " IS NOT NULL "
                        + "AND ps." + distCol + " <= :hardPoiMaxMeters)");
                } else if (countCol != null) {
                    conditions.add("EXISTS (SELECT 1 FROM properia.listing_poi_snapshots ps "
                        + "WHERE ps.listing_id = l.id AND ps." + countCol + " > 0)");
                }
            }
            if (!conditions.isEmpty()) {
                params.put("hardPoiMaxMeters", maxMeters);
                var joiner = "any".equals(p.hardPoisMode()) ? " OR " : " AND ";
                parts.add("(" + String.join(joiner, conditions) + ")");
            }
        }

        // Advertiser filter (agency profile page)
        if (p.advertiserId() != null && !p.advertiserId().isBlank()) {
            parts.add("l.advertiser_id = :advertiserId::uuid");
            params.put("advertiserId", p.advertiserId());
        }

        // Text search
        if (p.q() != null && !p.q().isBlank()) {
            parts.add("(l.title_normalized ILIKE :q OR l.city ILIKE :q OR l.district ILIKE :q OR l.parish ILIKE :q)");
            params.put("q", "%" + p.q().toLowerCase().strip() + "%");
        }

        String whereClause = parts.isEmpty()
            ? "WHERE 1=1"
            : "WHERE " + String.join(" AND ", parts);

        return new WhereClause(whereClause, params);
    }

    // Plano de ordenação: além da expressão ORDER BY, sorts mais ricos (value/score)
    // precisam de um CTE (comparáveis de preço) ou de parâmetros extra (POIs suaves).
    // cte fica vazio para sorts simples — o caminho rápido de sempre não muda.
    private record SortPlan(String cte, String fromExtra, String orderBy, java.util.Map<String, Object> params) {}

    private static final String FEATURED_BOOST_PREFIX =
        // Destaque só entra em "recente"/"score" (nunca distorce ordens explícitas
        // de preço/área/valor — isso quebraria a confiança de quem pediu "mais barato
        // primeiro"). Rotação diária pelo hash do id: evita que o mesmo anúncio em
        // destaque fique sempre no topo quando há vários anunciantes com destaque.
        "CASE WHEN l.is_featured THEN 0 ELSE 1 END ASC, "
        + "md5(l.id::text || to_char(CURRENT_DATE, 'YYYY-MM-DD')) ASC, ";

    private SortPlan buildSortPlan(SearchParams p) {
        var sort = p.sort() == null ? "recente" : p.sort();

        var defaultPlan = new SortPlan("", "", FEATURED_BOOST_PREFIX
            + "l.published_at DESC NULLS LAST, l.created_at DESC", java.util.Map.of());

        return switch (sort) {
            case "preco_asc" -> new SortPlan("", "", "l.price_amount ASC NULLS LAST", java.util.Map.of());
            case "preco_desc" -> new SortPlan("", "", "l.price_amount DESC NULLS LAST", java.util.Map.of());
            case "area" -> new SortPlan("", "", "l.usable_area_m2 DESC NULLS LAST", java.util.Map.of());

            // "Contexto de preço": €/m² deste imóvel vs. a média de imóveis publicados
            // comparáveis (mesma cidade + tipo + negócio). Melhor relação primeiro.
            // CTE calcula a média uma vez por combinação, não por linha — barato mesmo
            // com muitos resultados.
            case "value" -> new SortPlan(
                """
                comparables AS (
                    SELECT city, property_type, business_type,
                           AVG(price_amount / NULLIF(usable_area_m2, 0)) AS avg_ppm2
                    FROM properia.listings
                    WHERE status = 'published' AND usable_area_m2 > 0 AND price_amount IS NOT NULL
                    GROUP BY city, property_type, business_type
                )
                """,
                "LEFT JOIN comparables cmp ON cmp.city = l.city "
                + "AND cmp.property_type = l.property_type AND cmp.business_type = l.business_type",
                """
                (
                    CASE WHEN l.usable_area_m2 > 0 AND l.price_amount IS NOT NULL AND cmp.avg_ppm2 > 0
                         THEN (l.price_amount / l.usable_area_m2) / cmp.avg_ppm2
                         ELSE NULL
                    END
                ) ASC NULLS LAST, l.published_at DESC
                """,
                java.util.Map.of()
            );

            // "Compatibilidade estimada": relevância pelos POIs suaves da pesquisa
            // (proximidade real, via listing_poi_snapshots) + um impulso pequeno de
            // destaque que nunca decide sozinho — um destaque irrelevante não bate um
            // imóvel muito relevante sem destaque.
            case "score" -> buildScoreSortPlan(p, defaultPlan);

            default -> defaultPlan;
        };
    }

    private SortPlan buildScoreSortPlan(SearchParams p, SortPlan fallback) {
        var softPois = p.softPois();
        if (softPois == null || softPois.isEmpty()) {
            // Sem contexto semântico (ninguém escolheu isto por pesquisa/perfil) —
            // cai para destaque + recência, em vez de um "score" vazio sem sentido.
            return fallback;
        }

        int maxMeters = (p.zoneMaxMinutes() != null ? p.zoneMaxMinutes() : DEFAULT_ZONE_MAX_MINUTES)
            * WALK_METERS_PER_MINUTE;

        var terms = new ArrayList<String>();
        for (var poi : softPois) {
            var distCol = POI_DISTANCE_COLUMN.get(poi);
            if (distCol == null) continue; // praia/cultura/biblioteca: sem distância, não entram no score numérico
            terms.add("COALESCE((SELECT GREATEST(0, 1 - ps." + distCol + "::float / :scoreMaxMeters) "
                + "FROM properia.listing_poi_snapshots ps WHERE ps.listing_id = l.id "
                + "ORDER BY ps.processed_at DESC LIMIT 1), 0)");
        }

        if (terms.isEmpty()) return fallback;

        var avgProximity = "((" + String.join(" + ", terms) + ") / " + terms.size() + ".0)";
        var orderBy = "("
            + avgProximity + " * 0.85"
            + " + (CASE WHEN l.is_featured THEN 0.15 ELSE 0 END)"
            + ") DESC, l.published_at DESC";

        return new SortPlan("", "", orderBy, java.util.Map.of("scoreMaxMeters", maxMeters));
    }

    // ── Row mapper ─────────────────────────────────────────────────────────────

    private ListingSearchItemDto mapRow(ResultSet rs) throws SQLException {
        var featureTagsRaw = rs.getString("feature_tags");
        List<String> featureTags = featureTagsRaw != null && !featureTagsRaw.isBlank()
            ? parseJsonStringArray(featureTagsRaw)
            : List.of();

        var publishedAt = rs.getTimestamp("published_at");
        var updatedAt = rs.getTimestamp("updated_at");

        return new ListingSearchItemDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("public_id"),
            UUID.fromString(rs.getString("advertiser_id")),
            rs.getString("title"),
            rs.getString("business_type"),
            rs.getString("property_type"),
            rs.getString("status"),
            rs.getString("visibility_status"),
            rs.getBoolean("is_featured"),
            rs.getBigDecimal("price_amount"),
            rs.getString("price_currency"),
            rs.getBigDecimal("condo_fee"),
            rs.getBigDecimal("property_tax_annual"),
            rs.getBigDecimal("municipal_tax_estimate"),
            rs.getBigDecimal("deposit_required"),
            rs.getInt("bedrooms"),
            rs.getBigDecimal("bathrooms"),
            rs.getInt("suites"),
            (Integer) rs.getObject("garage_spaces"),
            (Integer) rs.getObject("parking_spaces"),
            rs.getBigDecimal("usable_area_m2"),
            rs.getBigDecimal("gross_area_m2"),
            rs.getBigDecimal("lot_area_m2"),
            rs.getString("city"),
            rs.getString("district"),
            rs.getString("parish"),
            rs.getString("neighborhood"),
            rs.getString("street"),
            rs.getString("postal_code"),
            rs.getString("location_precision"),
            (Double) rs.getObject("latitude"),
            (Double) rs.getObject("longitude"),
            rs.getString("hero_image_url"),
            parseImageUrls(rs),
            rs.getString("floorplan_url"),
            rs.getString("youtube_tour_url"),
            rs.getString("description_short"),
            rs.getString("energy_rating"),
            rs.getString("condition_final"),
            rs.getString("furnished_final"),
            rs.getBoolean("has_garage"),
            rs.getBoolean("has_private_parking"),
            rs.getBoolean("has_balcony"),
            rs.getBoolean("has_terrace"),
            rs.getBoolean("has_garden"),
            rs.getBoolean("has_pool"),
            rs.getBoolean("has_elevator"),
            rs.getBoolean("has_natural_light"),
            rs.getBoolean("has_equipped_kitchen"),
            rs.getBoolean("has_built_in_closets"),
            rs.getBoolean("has_double_glazing"),
            rs.getBoolean("has_solar_panels"),
            rs.getBoolean("has_barbecue"),
            rs.getBoolean("has_laundry_area"),
            rs.getString("pool_type"),
            featureTags,
            (Integer) rs.getObject("floor_number"),
            (Integer) rs.getObject("total_floors"),
            (Integer) rs.getObject("construction_year"),
            (Integer) rs.getObject("renovation_year"),
            rs.getString("sun_exposure"),
            buildTipologia(rs),
            rs.getBoolean("is_immediately_available"),
            rs.getString("available_from"),
            publishedAt != null ? publishedAt.toInstant() : null,
            updatedAt != null ? updatedAt.toInstant() : null,
            rs.getString("zone_label_primary"),
            rs.getString("zone_summary_short"),
            rs.getInt("detail_views_total"),
            buildPriceHistorySnapshot(rs),
            rs.getString("virtual_tour_url"),
            rs.getString("virtual_tour_status"),
            null  // commuteSummary — preenchido por SearchListingsUseCase quando pedido
        );
    }

    private PriceHistorySnapshotDto buildPriceHistorySnapshot(ResultSet rs) throws SQLException {
        int changeCount = rs.getInt("ph_change_count");
        if (changeCount == 0) return null;

        var firstPriceObj = rs.getObject("ph_first_price");
        if (firstPriceObj == null) return null;

        double firstPrice = ((Number) firstPriceObj).doubleValue();
        double currentPrice = rs.getBigDecimal("price_amount").doubleValue();
        var lastChangeAtTs = rs.getTimestamp("ph_last_change_at");
        String lastChangeAt = lastChangeAtTs != null ? lastChangeAtTs.toInstant().toString() : null;

        if (currentPrice < firstPrice - 0.01) {
            double delta = firstPrice - currentPrice;
            double pct = Math.round((delta / firstPrice) * 1000.0) / 10.0;
            return new PriceHistorySnapshotDto("down", pct, delta, lastChangeAt, changeCount);
        }
        if (currentPrice > firstPrice + 0.01) {
            double delta = currentPrice - firstPrice;
            double pct = Math.round((delta / firstPrice) * 1000.0) / 10.0;
            return new PriceHistorySnapshotDto("up", pct, delta, lastChangeAt, changeCount);
        }
        return new PriceHistorySnapshotDto("stable", null, null, null, changeCount);
    }

    private List<String> parseImageUrls(ResultSet rs) throws SQLException {
        var raw = rs.getString("image_urls_arr");
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split("\\|"));
    }

    private String buildTipologia(ResultSet rs) throws SQLException {
        int bedrooms = rs.getInt("bedrooms");
        String propertyType = rs.getString("property_type");
        if ("studio".equals(propertyType)) return "T0";
        if ("room".equals(propertyType)) return null;
        if (bedrooms == 0) return "T0";
        return "T" + bedrooms;
    }

    // ── Value mappers ──────────────────────────────────────────────────────────

    private String mapBusinessType(String negocio) {
        return switch (negocio) {
            case "venda" -> "sale";
            case "arrendamento" -> "rent";
            case "trespasse" -> "transfer";
            default -> null;
        };
    }

    // Aceita DOIS vocabulários: os ids canónicos do catálogo FE (lib/property-types.ts,
    // usados pela home, modal de filtros e cadastro) e os aliases dos parsers de
    // linguagem natural (estudio, quinta, villa, geminada). Sem os canónicos, filtrar
    // por "vivenda"/"moradia_geminada"/"quinta_herdade" caía no passthrough e comparava
    // com valores inexistentes no enum → 0 resultados silenciosos.
    private String mapPropertyType(String tipo) {
        return switch (tipo) {
            case "apartamento" -> "apartment";
            case "moradia" -> "house";
            case "studio", "estudio" -> "studio";
            case "penthouse" -> "penthouse";
            case "duplex" -> "duplex";
            case "loft" -> "loft";
            case "moradia_em_banda" -> "townhouse";
            case "moradia_geminada", "geminada" -> "semi_detached_house";
            case "vivenda", "villa" -> "villa";
            case "quarto" -> "room";
            case "terreno" -> "land";
            case "comercial" -> "commercial";
            case "escritorio" -> "office";
            case "loja" -> "shop";
            case "armazem" -> "warehouse";
            case "industrial" -> "industrial";
            case "garagem" -> "garage";
            case "quinta_herdade", "quinta" -> "farm";
            case "hotel" -> "hotel";
            case "predio" -> "building";
            default -> tipo; // pass through if already a db value
        };
    }

    private String mapMobilia(String mobilia) {
        return switch (mobilia) {
            case "mobilado" -> "furnished";
            case "semi-mobilado" -> "semi_furnished";
            case "sem-mobilia" -> "unfurnished";
            default -> null;
        };
    }

    private String mapFeatureToColumn(String feature) {
        return switch (feature) {
            // Portuguese UI slugs (sent by filter modal and parse-prompt)
            case "garagem"             -> "has_garage";
            case "estacionamento"      -> "has_private_parking";
            case "varanda"             -> "has_balcony";
            case "terraco"             -> "has_terrace";
            case "piscina",
                 "piscina_privada",
                 "piscina_condominio"  -> "has_pool";
            case "elevador"            -> "has_elevator";
            case "cozinha_equipada"    -> "has_equipped_kitchen";
            case "roupeiros_embutidos" -> "has_built_in_closets";
            case "ar_condicionado"     -> "has_air_conditioning";
            case "lareira"             -> "has_fireplace";
            case "arrecadacao"         -> "has_storage_room";
            case "lavandaria"          -> "has_laundry_area";
            case "vidros_duplos"       -> "has_double_glazing";
            case "paineis_solares"     -> "has_solar_panels";
            case "churrasqueira"       -> "has_barbecue";
            case "quintal"             -> "has_garden";
            case "vista_mar"           -> "has_sea_view";
            case "vista_rio"           -> "has_river_view";
            case "vista_verde"         -> "has_green_view";
            case "luz_natural"         -> "has_natural_light";
            // Legacy has_* keys (kept for backwards compatibility)
            case "has_elevator"        -> "has_elevator";
            case "has_pool"            -> "has_pool";
            case "has_garage"          -> "has_garage";
            case "has_balcony"         -> "has_balcony";
            case "has_terrace"         -> "has_terrace";
            case "has_garden"          -> "has_garden";
            case "has_storage_room"    -> "has_storage_room";
            case "has_air_conditioning"-> "has_air_conditioning";
            case "has_solar_panels"    -> "has_solar_panels";
            case "has_fireplace"       -> "has_fireplace";
            case "has_natural_light"   -> "has_natural_light";
            case "has_double_glazing"  -> "has_double_glazing";
            case "has_equipped_kitchen"-> "has_equipped_kitchen";
            case "has_open_kitchen"    -> "has_open_kitchen";
            case "has_built_in_closets"-> "has_built_in_closets";
            case "has_sea_view"        -> "has_sea_view";
            case "has_river_view"      -> "has_river_view";
            case "has_green_view"      -> "has_green_view";
            case "has_barbecue"        -> "has_barbecue";
            case "has_laundry_area"    -> "has_laundry_area";
            default -> null;
        };
    }

    private List<String> parseJsonStringArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
        var cleaned = json.trim().replaceAll("^\\[|]$", "");
        if (cleaned.isBlank()) return List.of();
        return Arrays.stream(cleaned.split(","))
            .map(s -> s.trim().replaceAll("^\"|\"$", ""))
            .filter(s -> !s.isBlank())
            .toList();
    }

    private record WhereClause(String sql, java.util.Map<String, Object> params) {}
}
