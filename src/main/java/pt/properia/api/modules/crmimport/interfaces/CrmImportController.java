package pt.properia.api.modules.crmimport.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.crmimport.application.CrmImportService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/crm/import")
public class CrmImportController {

    private final CrmImportService crmImportService;

    public CrmImportController(CrmImportService crmImportService) {
        this.crmImportService = crmImportService;
    }

    @GetMapping("/batches")
    public ResponseEntity<?> listBatches(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var batches = crmImportService.listBatches(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("items", batches)));
    }

    @PostMapping("/batches")
    public ResponseEntity<?> createBatch(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var batch = crmImportService.createBatch(
            advertiserId, claims.userId(),
            body.getOrDefault("sourceFamily", "manual"),
            body.getOrDefault("sourceChannel", "manual"),
            body.getOrDefault("ingestionMethod", "csv"),
            body.get("fileName")
        );
        return ResponseEntity.status(201).body(Map.of("data", batch));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<?> getBatch(
            @PathVariable UUID batchId,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var batch = crmImportService.getBatch(advertiserId, batchId);
        return ResponseEntity.ok(Map.of("data", batch));
    }

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

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
