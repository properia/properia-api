package pt.properia.api.modules.listingimport.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.listingimport.application.ListingImportService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.PlanAccessGuard;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Importador inteligente de inventário de imóveis (CSV / feed XML) com mapeamento
 * de campos assistido por IA. Cria SEMPRE rascunhos — a publicação continua a
 * passar pelos checks obrigatórios.
 *
 * Fluxo: analyze (preview, sem persistir) → commit (cria rascunhos).
 */
@RestController
@RequestMapping("/api/advertiser/listings/import")
public class ListingImportController {

    private static final int MAX_CONTENT_CHARS = 8_000_000; // ~8MB de texto

    private final ListingImportService service;
    private final PlanAccessGuard planGuard;

    public ListingImportController(ListingImportService service, PlanAccessGuard planGuard) {
        this.service = service;
        this.planGuard = planGuard;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var fileName = str(body, "fileName");
        var content = requireContent(body);
        var result = service.analyze(advertiserId, fileName, content);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PostMapping("/commit")
    public ResponseEntity<?> commit(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var fileName = str(body, "fileName");
        var content = requireContent(body);
        var exclude = excludeRowIndices(body);
        var result = service.commit(advertiserId, claims.userId(), fileName, content, exclude);
        return ResponseEntity.ok(Map.of("data", result));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        // Import de inventário está disponível no Pro e Business — impor no servidor.
        planGuard.requireProFeatures(claims.activeAdvertiserId());
        return claims.activeAdvertiserId();
    }

    private String requireContent(Map<String, Object> body) {
        var content = str(body, "content");
        if (content == null || content.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Nenhum ficheiro para importar.", 422);
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new DomainException("FILE_TOO_LARGE",
                "O ficheiro é demasiado grande. Divide-o em ficheiros mais pequenos.", 413);
        }
        return content;
    }

    private Set<Integer> excludeRowIndices(Map<String, Object> body) {
        var raw = body.get("excludeRowIndices");
        if (!(raw instanceof List<?> list)) return Set.of();
        return list.stream()
            .filter(v -> v instanceof Number)
            .map(v -> ((Number) v).intValue())
            .collect(Collectors.toSet());
    }

    private String str(Map<String, Object> body, String key) {
        var v = body.get(key);
        return v == null ? null : v.toString();
    }
}
