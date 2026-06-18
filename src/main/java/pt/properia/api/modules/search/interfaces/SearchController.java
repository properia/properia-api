package pt.properia.api.modules.search.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.search.application.SearchListingsUseCase;
import pt.properia.api.modules.search.application.dto.SearchParams;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchListingsUseCase useCase;
    private final JdbcClient jdbc;

    public SearchController(SearchListingsUseCase useCase, JdbcClient jdbc) {
        this.useCase = useCase;
        this.jdbc = jdbc;
    }

    // TEMPORARY diagnostic endpoint 2 — simulates full search() step by step
    @GetMapping("/listings/diag2")
    public ResponseEntity<?> diag2() {
        var r = new java.util.LinkedHashMap<String, Object>();
        long t;

        // Phase 1: same as search Phase 1
        t = System.currentTimeMillis();
        var ids = jdbc.sql("SELECT l.id::text AS id FROM properia.listings l WHERE l.status = 'published' ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC LIMIT :lim OFFSET :off")
            .param("lim", 6).param("off", 0).query(String.class).list();
        r.put("phase1_ms", System.currentTimeMillis() - t);
        r.put("ids", ids);

        if (!ids.isEmpty()) {
            var lit = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));

            // Phase 2a: just listings JOIN (no laterals)
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2a_joins_only_ms", System.currentTimeMillis() - t);

            // Phase 2b: add listing_media lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS c FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image') lm ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2b_media_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2c: add detail_views lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2c_detail_views_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2d: add price_history lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS change_count FROM properia.listing_price_history WHERE listing_id = l.id) ph ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2d_price_history_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2e: all 3 laterals combined
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS c FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image') lm ON true LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv ON true LEFT JOIN LATERAL (SELECT COUNT(*) AS change_count FROM properia.listing_price_history WHERE listing_id = l.id) ph ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2e_all_laterals_ms", System.currentTimeMillis() - t);

            // Phase 2f: FULL query with ARRAY_AGG laterals (matches actual search phase 2)
            t = System.currentTimeMillis();
            var fullSql = "SELECT l.id::text, lm.image_urls_arr, COALESCE(dv_agg.view_count,0) AS dv, COALESCE(ph_agg.change_count,0) AS ph"
                + " FROM properia.listings l"
                + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
                + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
                + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
                + " LEFT JOIN LATERAL (SELECT (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url, ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC) AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
                + " WHERE l.id::text IN (" + lit + ")";
            jdbc.sql(fullSql).query(String.class).list();
            r.put("phase2f_full_laterals_array_agg_ms", System.currentTimeMillis() - t);
        }

        // count() equivalent
        t = System.currentTimeMillis();
        long cnt = jdbc.sql("SELECT COUNT(*) FROM properia.listings l WHERE l.status = 'published'").query(Long.class).single();
        r.put("count_ms", System.currentTimeMillis() - t);
        r.put("count_val", cnt);

        return ResponseEntity.ok(r);
    }

    // TEMPORARY diagnostic endpoint — remove after root cause is identified
    @GetMapping("/listings/diag")
    public ResponseEntity<?> diag() {
        var results = new java.util.LinkedHashMap<String, Object>();

        // Step 1: bare connection ping
        long t = System.currentTimeMillis();
        jdbc.sql("SELECT 1").query(Integer.class).single();
        results.put("ping_ms", System.currentTimeMillis() - t);

        // Step 2: count published (no ORDER BY, no LIMIT)
        t = System.currentTimeMillis();
        long cnt = jdbc.sql("SELECT COUNT(*) FROM properia.listings WHERE status = 'published'")
            .query(Long.class).single();
        results.put("count_published_ms", System.currentTimeMillis() - t);
        results.put("count_published", cnt);

        // Step 3: select id (no ORDER BY, no LIMIT)
        t = System.currentTimeMillis();
        var ids1 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published'")
            .query(String.class).list();
        results.put("select_ids_no_order_ms", System.currentTimeMillis() - t);
        results.put("ids_count", ids1.size());

        // Step 4: select id with ORDER BY (no LIMIT)
        t = System.currentTimeMillis();
        var ids2 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST")
            .query(String.class).list();
        results.put("select_ids_with_order_ms", System.currentTimeMillis() - t);

        // Step 5: select id with ORDER BY + LIMIT (literal)
        t = System.currentTimeMillis();
        var ids3 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST LIMIT 24 OFFSET 0")
            .query(String.class).list();
        results.put("select_ids_order_limit_literal_ms", System.currentTimeMillis() - t);

        // Step 6: select id with ORDER BY + LIMIT (named param)
        t = System.currentTimeMillis();
        var ids4 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST LIMIT :lim OFFSET :off")
            .param("lim", 24).param("off", 0)
            .query(String.class).list();
        results.put("select_ids_order_limit_param_ms", System.currentTimeMillis() - t);

        // Step 7: count listing_detail_views (check table size)
        t = System.currentTimeMillis();
        long dvCount = jdbc.sql("SELECT COUNT(*) FROM properia.listing_detail_views")
            .query(Long.class).single();
        results.put("listing_detail_views_total_ms", System.currentTimeMillis() - t);
        results.put("listing_detail_views_count", dvCount);

        // Step 8: count listing_media (check table size)
        t = System.currentTimeMillis();
        long mediaCount = jdbc.sql("SELECT COUNT(*) FROM properia.listing_media")
            .query(Long.class).single();
        results.put("listing_media_total_ms", System.currentTimeMillis() - t);
        results.put("listing_media_count", mediaCount);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/listings")
    public ResponseEntity<?> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "todos") String negocio,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String quartos,
            @RequestParam(required = false) Double precoMin,
            @RequestParam(required = false) Double precoMax,
            @RequestParam(required = false) String certificado,
            @RequestParam(required = false) String mobilia,
            @RequestParam(required = false) Double bathroomMin,
            @RequestParam(required = false) Double areaMin,
            @RequestParam(required = false) Double areaMax,
            @RequestParam(required = false) String conditionStatus,
            @RequestParam(required = false) Integer floorMin,
            @RequestParam(required = false) String sunExposure,
            @RequestParam(required = false) String features,
            @RequestParam(required = false) String disponibilidade,
            @RequestParam(defaultValue = "recente") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize,
            @RequestParam(required = false) Double commuteLat,
            @RequestParam(required = false) Double commuteLng,
            @RequestParam(required = false) String commuteMode,
            @RequestParam(required = false) Integer commuteMaxMinutes,
            @RequestParam(defaultValue = "false") boolean roomHasPrivateBathroom,
            @RequestParam(defaultValue = "false") boolean roomBillsIncluded,
            @RequestParam(defaultValue = "false") boolean roomInternetIncluded,
            @RequestParam(defaultValue = "false") boolean roomCoupleAllowed,
            @RequestParam(defaultValue = "false") boolean roomIsExterior,
            @RequestParam(required = false) Integer roomMinStayMonths,
            @RequestParam(defaultValue = "false") boolean commercialHasShopfront,
            @RequestParam(required = false) String commercialStreetVisibility,
            @RequestParam(defaultValue = "false") boolean commercialHasVehicleAccess,
            @RequestParam(defaultValue = "false") boolean commercialHasFluePipe,
            @RequestParam(defaultValue = "false") boolean commercialHasExtractionSystem,
            @RequestParam(required = false) String commercialPermittedUse,
            @RequestParam(required = false) String advertiserId) {

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(48, Math.max(1, pageSize));

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features),
            disponibilidade != null ? disponibilidade : "",
            sort, safePage, safePageSize,
            commuteLat, commuteLng, commuteMode, commuteMaxMinutes,
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId
        );

        var result = useCase.search(params);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping("/listings/count")
    public ResponseEntity<?> count(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "todos") String negocio,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String quartos,
            @RequestParam(required = false) Double precoMin,
            @RequestParam(required = false) Double precoMax,
            @RequestParam(required = false) String certificado,
            @RequestParam(required = false) String mobilia,
            @RequestParam(required = false) Double bathroomMin,
            @RequestParam(required = false) Double areaMin,
            @RequestParam(required = false) Double areaMax,
            @RequestParam(required = false) String conditionStatus,
            @RequestParam(required = false) Integer floorMin,
            @RequestParam(required = false) String sunExposure,
            @RequestParam(required = false) String features,
            @RequestParam(required = false) String disponibilidade,
            @RequestParam(defaultValue = "false") boolean roomHasPrivateBathroom,
            @RequestParam(defaultValue = "false") boolean roomBillsIncluded,
            @RequestParam(defaultValue = "false") boolean roomInternetIncluded,
            @RequestParam(defaultValue = "false") boolean roomCoupleAllowed,
            @RequestParam(defaultValue = "false") boolean roomIsExterior,
            @RequestParam(required = false) Integer roomMinStayMonths,
            @RequestParam(defaultValue = "false") boolean commercialHasShopfront,
            @RequestParam(required = false) String commercialStreetVisibility,
            @RequestParam(defaultValue = "false") boolean commercialHasVehicleAccess,
            @RequestParam(defaultValue = "false") boolean commercialHasFluePipe,
            @RequestParam(defaultValue = "false") boolean commercialHasExtractionSystem,
            @RequestParam(required = false) String commercialPermittedUse,
            @RequestParam(required = false) String advertiserId) {

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features),
            disponibilidade != null ? disponibilidade : "",
            "recente", 1, 1,
            null, null, null, null,
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId
        );

        long total = useCase.count(params);
        return ResponseEntity.ok(Map.of("data", Map.of("total", total)));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private List<Integer> splitInts(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .flatMap(s -> {
                try {
                    return java.util.stream.Stream.of(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    return java.util.stream.Stream.empty();
                }
            })
            .toList();
    }
}
