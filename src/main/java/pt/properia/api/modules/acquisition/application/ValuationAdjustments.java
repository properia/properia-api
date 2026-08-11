package pt.properia.api.modules.acquisition.application;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Fatores multiplicativos aplicados ao €/m² base da zona.
 *
 * Classe sem estado e sem dependências, deliberadamente: é a parte do motor com
 * mais risco de erro silencioso e a que tem de ser testável de forma trivial.
 *
 * Os valores são heurísticos e calibráveis. O que NÃO é negociável é o teto
 * global: por muito que os fatores se acumulem, o resultado nunca se afasta mais
 * de 35% do preço da zona. Uma estimativa automática que produza metade ou o
 * dobro do mercado local está errada, independentemente do que os fatores digam.
 */
public final class ValuationAdjustments {

    private ValuationAdjustments() {}

    /** Teto de acumulação de fatores. */
    public static final double MIN_TOTAL_FACTOR = 0.65;
    public static final double MAX_TOTAL_FACTOR = 1.35;

    /** Tipos onde andar, elevador e tipologia fazem sentido. Num terreno ou
     *  armazém, "3.º andar sem elevador" não significa nada. */
    private static final Set<String> RESIDENTIAL_TYPES = Set.of(
        "apartment", "house", "studio", "penthouse", "duplex", "loft",
        "townhouse", "semi_detached_house", "villa"
    );

    /** Tipos onde o número de quartos define uma área esperada. Moradias variam
     *  demasiado para a heurística de área/tipologia ser fiável. */
    private static final Set<String> TYPOLOGY_TYPES = Set.of(
        "apartment", "studio", "penthouse", "duplex", "loft"
    );

    // ── Estado de conservação ─────────────────────────────────────────────────

    public static double conditionFactor(String conditionStatus) {
        if (conditionStatus == null) return 1.00;
        return switch (conditionStatus) {
            case "new"                -> 1.10;
            case "remodeled"          -> 1.06;
            case "under_construction" -> 1.02;
            case "used_good"          -> 1.00;
            case "used_regular"       -> 0.94;
            case "to_renovate"        -> 0.82;
            case "shell_core"         -> 0.70;
            default                   -> 1.00;
        };
    }

    // ── Andar e elevador ──────────────────────────────────────────────────────

    /**
     * Rés-do-chão desvaloriza (ruído, segurança, privacidade). Acima do 1.º sem
     * elevador desvaloriza progressivamente. Andares altos COM elevador
     * valorizam (vista, luz).
     */
    public static double floorFactor(String propertyType, Integer floorNumber, Boolean hasElevator) {
        if (!RESIDENTIAL_TYPES.contains(propertyType)) return 1.00;
        if (floorNumber == null) return 1.00;

        boolean elevator = Boolean.TRUE.equals(hasElevator);

        if (floorNumber <= 0) return 0.97;

        if (!elevator) {
            // -3% por andar acima do 1.º, com chão em 0.88 (a partir do 5.º o
            // desconto deixa de aumentar — quem compra a 5.º sem elevador já
            // aceitou o problema).
            double factor = 1.00 - 0.03 * (floorNumber - 1);
            return Math.max(0.88, factor);
        }

        return floorNumber >= 4 ? 1.02 : 1.00;
    }

    // ── Certificado energético ────────────────────────────────────────────────

    public static double energyFactor(String energyRating) {
        if (energyRating == null || energyRating.isBlank()) return 1.00;
        var normalized = energyRating.trim().toUpperCase();
        // "A+" e "A" partilham prefixo; testar o mais específico primeiro.
        if (normalized.startsWith("A+")) return 1.06;
        return switch (normalized.charAt(0)) {
            case 'A' -> 1.05;
            case 'B' -> 1.02;
            case 'C' -> 1.00;
            case 'D' -> 0.98;
            case 'E' -> 0.96;
            case 'F' -> 0.94;
            case 'G' -> 0.92;
            default  -> 1.00;
        };
    }

    // ── Rácio área / tipologia ────────────────────────────────────────────────

    /** Área típica em m² para cada tipologia (T0 a T5); acima de T5, +35 m² por quarto. */
    public static double expectedAreaFor(int bedrooms) {
        return switch (Math.max(0, bedrooms)) {
            case 0 -> 40;
            case 1 -> 60;
            case 2 -> 85;
            case 3 -> 110;
            case 4 -> 140;
            case 5 -> 170;
            default -> 170 + 35.0 * (bedrooms - 5);
        };
    }

    /**
     * Imóveis maiores do que o típico para a sua tipologia transacionam a um €/m²
     * mais baixo (o preço total não escala linearmente com a área), e vice-versa.
     * Correção suave e limitada — é uma tendência de mercado, não uma lei.
     */
    public static double areaTypologyFactor(String propertyType, Integer bedrooms, BigDecimal usableAreaM2) {
        if (!TYPOLOGY_TYPES.contains(propertyType)) return 1.00;
        if (bedrooms == null || usableAreaM2 == null || usableAreaM2.signum() <= 0) return 1.00;

        double expected = expectedAreaFor(bedrooms);
        if (expected <= 0) return 1.00;

        double ratio = usableAreaM2.doubleValue() / expected;
        double factor = 1.0 + (1.0 - ratio) * 0.10;
        return clamp(factor, 0.93, 1.06);
    }

    // ── Combinação ────────────────────────────────────────────────────────────

    public static double totalFactor(ValuationInput input) {
        double combined = conditionFactor(input.conditionStatus())
            * floorFactor(input.propertyType(), input.floorNumber(), input.hasElevator())
            * energyFactor(input.energyRating())
            * areaTypologyFactor(input.propertyType(), input.bedrooms(), input.usableAreaM2());

        return clamp(combined, MIN_TOTAL_FACTOR, MAX_TOTAL_FACTOR);
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
