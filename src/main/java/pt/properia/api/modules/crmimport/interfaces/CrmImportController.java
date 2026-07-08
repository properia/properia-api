package pt.properia.api.modules.crmimport.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.crmimport.application.CrmImportService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/crm/import")
public class CrmImportController {

    private final CrmImportService crmImportService;
    private final pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard;

    public CrmImportController(CrmImportService crmImportService,
                             pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard) {
        this.crmImportService = crmImportService;
        this.planGuard = planGuard;
    }

    // ── Batches ───────────────────────────────────────────────────────────────

    @GetMapping("/batches")
    public ResponseEntity<?> listBatches(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var batches = crmImportService.listBatches(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("items", batches)));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<?> getBatch(
            @PathVariable UUID batchId,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var batch = crmImportService.getBatch(advertiserId, batchId);
        return ResponseEntity.ok(Map.of("data", batch));
    }

    // ── Lead import ───────────────────────────────────────────────────────────

    @PostMapping("/leads")
    public ResponseEntity<?> previewLeads(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) body.getOrDefault("rows", List.of());
        var preview = crmImportService.previewLeads(advertiserId, rows);
        return ResponseEntity.ok(Map.of("data", preview));
    }

    @PostMapping("/leads/batches")
    public ResponseEntity<?> createLeadBatch(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) body.getOrDefault("rows", List.of());
        var result = crmImportService.createLeadBatch(
            advertiserId,
            claims.userId(),
            strOrDefault(body, "sourceFamily", "manual"),
            strOrDefault(body, "sourceChannel", "manual"),
            strOrDefault(body, "ingestionMethod", "csv"),
            (String) body.get("fileName"),
            rows
        );
        return ResponseEntity.status(201).body(Map.of("data", result));
    }

    // ── Visit import ──────────────────────────────────────────────────────────

    @PostMapping("/visits")
    public ResponseEntity<?> previewVisits(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) body.getOrDefault("rows", List.of());
        var preview = crmImportService.previewVisits(advertiserId, rows);
        return ResponseEntity.ok(Map.of("data", preview));
    }

    @PostMapping("/visits/batches")
    public ResponseEntity<?> createVisitBatch(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) body.getOrDefault("rows", List.of());
        var result = crmImportService.createVisitBatch(
            advertiserId,
            claims.userId(),
            strOrDefault(body, "sourceFamily", "manual"),
            strOrDefault(body, "sourceChannel", "manual"),
            strOrDefault(body, "ingestionMethod", "csv"),
            (String) body.get("fileName"),
            rows
        );
        return ResponseEntity.status(201).body(Map.of("data", result));
    }

    // ── Review queue ──────────────────────────────────────────────────────────

    @GetMapping("/review-queue")
    public ResponseEntity<?> getReviewQueue(
            @RequestParam(required = false) UUID batchId,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var items = crmImportService.listItems(advertiserId, batchId, "ambiguous");
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping("/items/{itemId}/decision")
    public ResponseEntity<?> applyDecision(
            @PathVariable UUID itemId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        crmImportService.applyItemDecision(advertiserId, itemId, body.get("action"), body.get("reason"));
        return ResponseEntity.ok(Map.of("data", Map.of("applied", true)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        // Importação de dados é Business — impor no servidor.
        planGuard.requireBusinessFeatures(claims.activeAdvertiserId());
        return claims.activeAdvertiserId();
    }

    private String strOrDefault(Map<String, Object> body, String key, String defaultVal) {
        var val = body.get(key);
        return val != null ? val.toString() : defaultVal;
    }
}
