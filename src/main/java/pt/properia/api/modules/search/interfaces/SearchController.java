package pt.properia.api.modules.search.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.search.application.SearchListingsUseCase;
import pt.properia.api.modules.search.application.dto.AdvancedSearchFilters;
import pt.properia.api.modules.search.application.dto.SearchParams;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchListingsUseCase useCase;

    public SearchController(SearchListingsUseCase useCase) {
        this.useCase = useCase;
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
            @RequestParam(required = false) String excludeFeatures,
            @RequestParam(required = false) String estilos,
            @RequestParam(required = false) String landType,
            @RequestParam(required = false) String disponibilidade,
            @RequestParam(defaultValue = "recente") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize,
            @RequestParam(required = false) Double commuteLat,
            @RequestParam(required = false) Double commuteLng,
            @RequestParam(required = false) String commuteMode,
            @RequestParam(required = false) Integer commuteMaxMinutes,
            @RequestParam(required = false) String hardPois,
            @RequestParam(required = false) String softPois,
            @RequestParam(required = false) Integer zoneMaxMinutes,
            @RequestParam(defaultValue = "all") String hardPoisMode,
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
            @RequestParam(required = false) String advertiserId,
            HttpServletRequest request) {

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(48, Math.max(1, pageSize));

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features), splitCsv(excludeFeatures), splitCsv(estilos), splitCsv(landType),
            disponibilidade != null ? disponibilidade : "",
            sort, safePage, safePageSize,
            commuteLat, commuteLng, commuteMode, commuteMaxMinutes,
            splitCsv(hardPois), splitCsv(softPois), zoneMaxMinutes,
            hardPoisMode != null ? hardPoisMode : "all",
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId,
            buildAdvanced(request)
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
            @RequestParam(required = false) String excludeFeatures,
            @RequestParam(required = false) String estilos,
            @RequestParam(required = false) String landType,
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
            @RequestParam(required = false) String advertiserId,
            HttpServletRequest request) {

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features), splitCsv(excludeFeatures), splitCsv(estilos), splitCsv(landType),
            disponibilidade != null ? disponibilidade : "",
            "recente", 1, 1,
            null, null, null, null,
            List.of(), List.of(), null, "all",
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId,
            buildAdvanced(request)
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

    // Lê os filtros avançados diretamente do request — evita ~44 @RequestParam extra.
    private AdvancedSearchFilters buildAdvanced(HttpServletRequest r) {
        return new AdvancedSearchFilters(
            boolParam(r, "fibraOtica"),
            boolParam(r, "gasCanalizado"),
            boolParam(r, "tvCabo"),
            boolParam(r, "fossaSeptica"),
            boolParam(r, "seguroCondominioIncluido"),
            splitCsv(r.getParameter("heatingTypes")),
            boolParam(r, "hasAirConditioning"),
            splitCsv(r.getParameter("waterHeatingTypes")),
            boolParam(r, "vidrosDuplos"),
            splitCsv(r.getParameter("buildingPositions")),
            intParam(r, "suitesMin"),
            boolParam(r, "hasWcServico"),
            doubleParam(r, "condoFeeMax"),
            doubleParam(r, "propertyTaxMax"),
            doubleParam(r, "depositMax"),
            intParam(r, "constructionYearMin"),
            boolParam(r, "commercialHasWc"),
            boolParam(r, "commercialHasKitchenette"),
            boolParam(r, "commercialHasOutdoorSeating"),
            intParam(r, "commercialInternalFloorsMin"),
            splitCsv(r.getParameter("waterSources")),
            boolParam(r, "agriculturalUse")
        );
    }

    private boolean boolParam(HttpServletRequest r, String name) {
        var v = r.getParameter(name);
        // Aceita as convenções usadas pelo frontend ("1") e por Spring ("true"/"on"/"yes").
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1")
            || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes"));
    }

    private Integer intParam(HttpServletRequest r, String name) {
        var v = r.getParameter(name);
        if (v == null || v.isBlank()) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Double doubleParam(HttpServletRequest r, String name) {
        var v = r.getParameter(name);
        if (v == null || v.isBlank()) return null;
        try { return Double.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
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
