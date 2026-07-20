package pt.properia.api.modules.search.application.dto;

import java.util.List;

public record SearchParams(
    String q,
    String negocio,          // "todos" | "venda" | "arrendamento"
    List<String> tipo,
    List<Integer> quartos,
    Double precoMin,
    Double precoMax,
    List<String> certificado,
    List<String> mobilia,
    Double bathroomMin,
    Double areaMin,
    Double areaMax,
    List<String> conditionStatus,
    Integer floorMin,
    List<String> sunExposure,
    List<String> features,
    List<String> excludeFeatures,   // features negadas ("sem varanda") — a excluir
    List<String> estilos,           // estilos arquitetónicos (via Vision AI: listing_ai_vision)
    List<String> landTypes,         // terreno: urbano | urbanizavel | rustico | agricola
    String disponibilidade,
    String sort,             // "recente" | "preco_asc" | "preco_desc" | "area" | "value" | "score"
    int page,
    int pageSize,
    // Commute filter
    Double commuteLat,
    Double commuteLng,
    String commuteMode,
    Integer commuteMaxMinutes,
    // Pontos de interesse (parser NL/IA) — hard = filtro obrigatório, soft = só
    // pesa na ordenação por relevância. Categorias: transporte, escola,
    // supermercado, saude, parque, ginasio, restaurante, cafe, farmacia, banco,
    // praia, cultura, biblioteca (as 3 últimas sem distância exata — ver repo).
    List<String> hardPois,
    List<String> softPois,
    Integer zoneMaxMinutes,
    String hardPoisMode,      // "any" | "all" — default "all"
    // Room filters
    boolean roomHasPrivateBathroom,
    boolean roomBillsIncluded,
    boolean roomInternetIncluded,
    boolean roomCoupleAllowed,
    boolean roomIsExterior,
    Integer roomMinStayMonths,
    // Commercial filters
    boolean commercialHasShopfront,
    List<String> commercialStreetVisibility,
    boolean commercialHasVehicleAccess,
    boolean commercialHasFluePipe,
    boolean commercialHasExtractionSystem,
    List<String> commercialPermittedUse,
    String advertiserId,
    AdvancedSearchFilters advanced
) {
    public static SearchParams defaults() {
        return new SearchParams(
            "", "todos", List.of(), List.of(),
            null, null, List.of(), List.of(),
            null, null, null, List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), "",
            "recente", 1, 24,
            null, null, null, null,
            List.of(), List.of(), null, "all",
            false, false, false, false, false, null,
            false, List.of(), false, false, false, List.of(),
            null,
            AdvancedSearchFilters.empty()
        );
    }
}
