package pt.properia.api.modules.acquisition.application;

import java.math.BigDecimal;

/**
 * Um nível da cascata que produziu um preço por m² utilizável.
 *
 * @param source      um de: listings_parish | listings_municipality | market_benchmark
 * @param scopeLabel  âmbito legível para mostrar ao proprietário ("Freguesia de Ramalde")
 */
public record MarketSample(
    BigDecimal pricePerM2,
    int sampleSize,
    String source,
    String scopeLabel
) {

    /** Amostra mínima. Um ou dois imóveis não são amostra — induzem em erro com
     *  aparência de rigor. É a mesma guarda que /api/listings/price-suggestion
     *  já impõe, e é deliberadamente conservadora. */
    public static final int MIN_SAMPLE_SIZE = 3;

    /** Limites de sanidade para o €/m² em Portugal. Fora disto, o dado está
     *  corrompido (área em cm², preço em cêntimos, anúncio de teste) e é melhor
     *  descer na cascata do que devolver lixo com ar de estimativa. */
    public static final BigDecimal MIN_PPM2 = new BigDecimal("250");
    public static final BigDecimal MAX_PPM2 = new BigDecimal("20000");

    public boolean isUsable() {
        return pricePerM2 != null
            && sampleSize >= MIN_SAMPLE_SIZE
            && pricePerM2.compareTo(MIN_PPM2) >= 0
            && pricePerM2.compareTo(MAX_PPM2) <= 0;
    }
}
