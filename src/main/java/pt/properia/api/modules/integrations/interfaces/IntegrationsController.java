package pt.properia.api.modules.integrations.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.integrations.application.IntegrationsService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/integrations")
public class IntegrationsController {

    private final IntegrationsService integrationsService;
    private final pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard;

    public IntegrationsController(IntegrationsService integrationsService,
                                 pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard) {
        this.integrationsService = integrationsService;
        this.planGuard = planGuard;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var items = integrationsService.listIntegrations(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var settings = body.get("settings") instanceof Map<?,?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        var integration = integrationsService.createIntegration(
            advertiserId,
            (String) body.get("integrationType"),
            (String) body.get("channel"),
            settings
        );
        return ResponseEntity.status(201).body(Map.of("data", integration));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var integration = integrationsService.getIntegration(advertiserId, id);
        return ResponseEntity.ok(Map.of("data", integration));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        @SuppressWarnings("unchecked")
        var settings = body.get("settings") instanceof Map<?,?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        var integration = integrationsService.updateIntegration(
            advertiserId, id, settings, (String) body.get("status"));
        return ResponseEntity.ok(Map.of("data", integration));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        integrationsService.deleteIntegration(advertiserId, id);
        return ResponseEntity.ok(Map.of("data", Map.of("deleted", true)));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        // Canais automáticos / integrações são Business — impor no servidor.
        planGuard.requireBusinessFeatures(claims.activeAdvertiserId());
        return claims.activeAdvertiserId();
    }
}
