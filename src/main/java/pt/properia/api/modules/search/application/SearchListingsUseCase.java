package pt.properia.api.modules.search.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.commute.infrastructure.OpenRouteCommuteService;
import pt.properia.api.modules.search.application.dto.CommuteSummaryDto;
import pt.properia.api.modules.search.application.dto.ListingSearchItemDto;
import pt.properia.api.modules.search.application.dto.SearchParams;
import pt.properia.api.modules.search.application.dto.SearchResultDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class SearchListingsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SearchListingsUseCase.class);

    private final SearchRepository repository;
    private final OpenRouteCommuteService commuteService;

    public SearchListingsUseCase(SearchRepository repository, OpenRouteCommuteService commuteService) {
        this.repository = repository;
        this.commuteService = commuteService;
    }

    public SearchResultDto search(SearchParams params) {
        var result = repository.search(params);

        if (hasCommuteFilter(params)) {
            var enriched = enrichWithCommute(result.items(), params);
            return new SearchResultDto(enriched, result.total(), result.page(), result.pageSize(), result.totalPages(), result.rankingSummary());
        }

        return result;
    }

    public long count(SearchParams params) {
        return repository.count(params);
    }

    // ── Enriquecimento com dados de trajeto ───────────────────────────────────

    private boolean hasCommuteFilter(SearchParams p) {
        return p.commuteLat() != null && p.commuteLng() != null && p.commuteMode() != null;
    }

    private List<ListingSearchItemDto> enrichWithCommute(List<ListingSearchItemDto> items, SearchParams params) {
        // Apenas imóveis com coordenadas válidas participam no cálculo
        var withCoords = items.stream()
            .filter(i -> i.latitude() != null && i.longitude() != null)
            .toList();

        if (withCoords.isEmpty()) return items;

        // Limitar a 49 itens por chamada (ORS free tier: 50 locations total incl. destino)
        var batch = withCoords.size() > 49 ? withCoords.subList(0, 49) : withCoords;

        var origins = batch.stream()
            .map(i -> new double[]{i.longitude(), i.latitude()})
            .toList();

        List<OpenRouteCommuteService.MatrixResult> matrix;
        try {
            matrix = commuteService.getMatrix(
                params.commuteLat(), params.commuteLng(),
                origins, params.commuteMode()
            );
        } catch (Exception e) {
            log.warn("Commute matrix falhou — a devolver listings sem commuteSummary: {}", e.getMessage());
            return items;
        }

        // Indexar resultados por id do imóvel
        var summaryById = new HashMap<UUID, CommuteSummaryDto>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            var item   = batch.get(i);
            var result = matrix.get(i);
            int durationMinutes = (int) Math.round(result.durationSeconds() / 60.0);
            boolean withinMax = params.commuteMaxMinutes() == null
                    || durationMinutes <= params.commuteMaxMinutes();

            summaryById.put(item.id(), new CommuteSummaryDto(
                null,                       // destinationLabel preenchido no FE via commuteLabel param
                params.commuteMode(),
                durationMinutes,
                Math.round(result.distanceKm() * 10.0) / 10.0,
                params.commuteMaxMinutes(),
                withinMax
            ));
        }

        // Reconstruir lista com o campo commuteSummary injectado
        return items.stream()
            .map(item -> {
                var cs = summaryById.get(item.id());
                return cs != null ? item.withCommuteSummary(cs) : item;
            })
            .toList();
    }
}
