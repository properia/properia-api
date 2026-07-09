package pt.properia.api.modules.search.application.dto;

import java.util.List;

/**
 * Filtros avançados do formulário de criar imóvel que antes não eram pesquisáveis.
 * Agrupados num record aninhado para não inchar o record posicional {@link SearchParams}.
 *
 * Grupos: infraestruturas, sistemas & eficiência, divisões, custos, construção,
 * comercial e rural/terreno.
 */
public record AdvancedSearchFilters(
    // ── Infraestruturas (booleans na tabela listings) ──
    boolean fibraOtica,
    boolean gasCanalizado,
    boolean tvCabo,
    boolean fossaSeptica,
    boolean seguroCondominioIncluido,
    // ── Sistemas & eficiência ──
    List<String> heatingTypes,        // l.heating_type = ANY
    boolean hasAirConditioning,       // l.cooling_type presente e != 'none'
    List<String> waterHeatingTypes,   // l.water_heating_type = ANY
    boolean vidrosDuplos,             // l.tipo_caixilharia IN (pvc_duplo, aluminio_termico)
    List<String> buildingPositions,   // l.localizacao_edificio = ANY
    // ── Divisões ──
    Integer suitesMin,                // l.suites >= x
    boolean hasWcServico,             // l.wc_servico >= 1
    // ── Custos ──
    Double condoFeeMax,               // l.condo_fee <= x
    Double propertyTaxMax,            // l.property_tax_annual <= x
    Double depositMax,                // l.deposit_required <= x
    // ── Construção ──
    Integer constructionYearMin,      // l.construction_year >= x
    // ── Comercial (sub-tabela listing_commercial_details) ──
    boolean commercialHasWc,
    boolean commercialHasKitchenette,
    boolean commercialHasOutdoorSeating,
    Integer commercialInternalFloorsMin,
    // ── Rural / terreno ──
    List<String> waterSources,        // l.water_source = ANY
    boolean agriculturalUse           // l.agricultural_use = true
) {
    public static AdvancedSearchFilters empty() {
        return new AdvancedSearchFilters(
            false, false, false, false, false,
            List.of(), false, List.of(), false, List.of(),
            null, false,
            null, null, null,
            null,
            false, false, false, null,
            List.of(), false
        );
    }
}
