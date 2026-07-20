package pt.properia.api.modules.search.application.dto;

import java.util.List;

/**
 * Explica ao utilizador porque os resultados estão nesta ordem — só existe
 * (mode="semantic") quando a ordenação usou dados reais (POIs suaves ou
 * comparáveis de preço), nunca para os sorts simples (preço/área/recente).
 */
public record SearchRankingSummaryDto(
    String mode,           // "default" | "semantic"
    String headline,
    String details,
    List<String> parameters,
    String legalNote
) {
    public static SearchRankingSummaryDto defaultMode() {
        return new SearchRankingSummaryDto("default", "", "", List.of(), null);
    }
}
