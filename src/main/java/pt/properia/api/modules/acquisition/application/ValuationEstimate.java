package pt.properia.api.modules.acquisition.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resultado do motor de estimativa.
 *
 * Nunca expõe um valor único: só intervalo. Um número exato numa estimativa
 * automática transmite uma precisão que os dados não suportam, e é exatamente o
 * que gera reclamações seis meses depois.
 *
 * @param inputs entradas e fatores aplicados, para auditabilidade — persistido
 *               em property_valuation_requests.estimate_inputs
 */
public record ValuationEstimate(
    boolean available,
    BigDecimal min,
    BigDecimal max,
    BigDecimal pricePerM2,
    String confidence,
    int sampleSize,
    String source,
    String scopeLabel,
    Map<String, Object> inputs
) {

    public static ValuationEstimate unavailable(Map<String, Object> inputs) {
        return new ValuationEstimate(
            false, null, null, null, null, 0, "none", null,
            inputs != null ? inputs : new LinkedHashMap<>()
        );
    }
}
