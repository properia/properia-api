package pt.properia.api.modules.acquisition.application;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Motor de avaliação indicativa de imóveis.
 *
 * Cascata, da fonte mais precisa para a mais genérica:
 *   1. Mediana do €/m² de anúncios publicados na FREGUESIA
 *   2. Idem no CONCELHO
 *   3. Benchmark oficial (INE) em market_price_benchmarks
 *   4. Sem estimativa
 *
 * Em cada nível vale a guarda de amostra mínima ({@link MarketSample#MIN_SAMPLE_SIZE}):
 * uma mediana de dois anúncios não é informação, é ruído com aparência de rigor.
 * Se um nível não passa a guarda, desce-se — nunca se devolve o que não presta.
 *
 * Mediana e não média, propositadamente: um único penthouse de luxo não pode
 * arrastar a estimativa de toda a freguesia.
 *
 * IMPORTANTE: o resultado é uma estimativa indicativa e não uma avaliação
 * imobiliária certificada. Quem consome este serviço tem de apresentar essa
 * ressalva ao utilizador final.
 */
@Service
public class ValuationEstimateService {

    /** Meia-largura do intervalo, por nível de confiança. Menos dados → intervalo
     *  mais largo. É a forma honesta de comunicar incerteza. */
    private static final Map<String, Double> BAND_BY_CONFIDENCE = Map.of(
        "high",   0.08,
        "medium", 0.12,
        "low",    0.18
    );

    private final MarketPriceRepository marketPrices;

    public ValuationEstimateService(MarketPriceRepository marketPrices) {
        this.marketPrices = marketPrices;
    }

    public ValuationEstimate estimate(ValuationInput input) {
        var inputs = describeInputs(input);

        if (input == null || !input.isCalculable()) {
            inputs.put("rejectedReason", "insufficient_input");
            return ValuationEstimate.unavailable(inputs);
        }

        var sample = resolveSample(input, inputs);
        if (sample.isEmpty()) {
            inputs.put("rejectedReason", "no_comparables");
            return ValuationEstimate.unavailable(inputs);
        }

        var base = sample.get();
        double factor = ValuationAdjustments.totalFactor(input);
        String confidence = confidenceFor(base);
        double band = BAND_BY_CONFIDENCE.getOrDefault(confidence, 0.18);

        var adjustedPpm2 = base.pricePerM2()
            .multiply(BigDecimal.valueOf(factor))
            .setScale(2, RoundingMode.HALF_UP);

        var central = adjustedPpm2.multiply(input.usableAreaM2());
        var min = roundToNearest(central.multiply(BigDecimal.valueOf(1 - band)), 500);
        var max = roundToNearest(central.multiply(BigDecimal.valueOf(1 + band)), 500);

        // O arredondamento pode colapsar o intervalo em imóveis muito baratos.
        if (min.compareTo(max) >= 0) {
            max = min.add(BigDecimal.valueOf(500));
        }

        inputs.put("basePricePerM2", base.pricePerM2());
        inputs.put("baseSource", base.source());
        inputs.put("baseScopeLabel", base.scopeLabel());
        inputs.put("baseSampleSize", base.sampleSize());
        inputs.put("factors", describeFactors(input, factor));
        inputs.put("confidence", confidence);
        inputs.put("bandPct", band);
        inputs.put("adjustedPricePerM2", adjustedPpm2);
        inputs.put("engineVersion", 1);

        return new ValuationEstimate(
            true, min, max, adjustedPpm2, confidence,
            base.sampleSize(), base.source(), base.scopeLabel(), inputs
        );
    }

    // ── Cascata ───────────────────────────────────────────────────────────────

    private Optional<MarketSample> resolveSample(ValuationInput input, Map<String, Object> inputs) {
        var attempts = new java.util.ArrayList<Map<String, Object>>();

        if (input.parish() != null) {
            var byParish = marketPrices.listingsMedianByParish(
                input.propertyType(), input.businessType(), input.municipality(), input.parish());
            attempts.add(describeAttempt("listings_parish", byParish));
            if (byParish.filter(MarketSample::isUsable).isPresent()) {
                inputs.put("cascade", attempts);
                return byParish;
            }
        }

        var byMunicipality = marketPrices.listingsMedianByMunicipality(
            input.propertyType(), input.businessType(), input.municipality());
        attempts.add(describeAttempt("listings_municipality", byMunicipality));
        if (byMunicipality.filter(MarketSample::isUsable).isPresent()) {
            inputs.put("cascade", attempts);
            return byMunicipality;
        }

        var benchmark = marketPrices.benchmark(
            input.propertyType(), input.businessType(),
            input.district(), input.municipality(), input.parish(), input.bedrooms());
        attempts.add(describeAttempt("market_benchmark", benchmark));
        inputs.put("cascade", attempts);

        return benchmark.filter(MarketSample::isUsable);
    }

    // ── Confiança ─────────────────────────────────────────────────────────────

    /**
     * O nível de confiança depende do tamanho da amostra E do âmbito. Uma
     * mediana de concelho, por muitos anúncios que tenha, nunca é "alta": mistura
     * zonas com preços muito diferentes dentro do mesmo concelho.
     */
    private String confidenceFor(MarketSample sample) {
        int n = sample.sampleSize();
        return switch (sample.source()) {
            case "listings_parish" -> n >= 20 ? "high" : n >= 8 ? "medium" : "low";
            case "listings_municipality" -> n >= 30 ? "medium" : "low";
            case "market_benchmark" -> n >= 30 ? "medium" : "low";
            default -> "low";
        };
    }

    // ── Auditabilidade ────────────────────────────────────────────────────────

    private Map<String, Object> describeInputs(ValuationInput input) {
        var m = new LinkedHashMap<String, Object>();
        if (input == null) return m;
        m.put("propertyType", input.propertyType());
        m.put("businessType", input.businessType());
        m.put("district", input.district());
        m.put("municipality", input.municipality());
        m.put("parish", input.parish());
        m.put("bedrooms", input.bedrooms());
        m.put("usableAreaM2", input.usableAreaM2());
        m.put("conditionStatus", input.conditionStatus());
        m.put("floorNumber", input.floorNumber());
        m.put("hasElevator", input.hasElevator());
        m.put("energyRating", input.energyRating());
        return m;
    }

    private Map<String, Object> describeFactors(ValuationInput input, double total) {
        var m = new LinkedHashMap<String, Object>();
        m.put("condition", ValuationAdjustments.conditionFactor(input.conditionStatus()));
        m.put("floor", ValuationAdjustments.floorFactor(
            input.propertyType(), input.floorNumber(), input.hasElevator()));
        m.put("energy", ValuationAdjustments.energyFactor(input.energyRating()));
        m.put("areaTypology", ValuationAdjustments.areaTypologyFactor(
            input.propertyType(), input.bedrooms(), input.usableAreaM2()));
        m.put("total", total);
        return m;
    }

    private Map<String, Object> describeAttempt(String source, Optional<MarketSample> sample) {
        var m = new LinkedHashMap<String, Object>();
        m.put("source", source);
        m.put("sampleSize", sample.map(MarketSample::sampleSize).orElse(0));
        m.put("pricePerM2", sample.map(MarketSample::pricePerM2).orElse(null));
        m.put("accepted", sample.filter(MarketSample::isUsable).isPresent());
        return m;
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    /** Arredonda para o múltiplo mais próximo. Mostrar "247.318 €" numa
     *  estimativa é fingir uma precisão que não existe. */
    static BigDecimal roundToNearest(BigDecimal value, int step) {
        return value
            .divide(BigDecimal.valueOf(step), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(step))
            .setScale(2, RoundingMode.HALF_UP);
    }
}
