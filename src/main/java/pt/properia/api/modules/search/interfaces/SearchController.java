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
