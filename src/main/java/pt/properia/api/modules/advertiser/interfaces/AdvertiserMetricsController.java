package pt.properia.api.modules.advertiser.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.advertiser.application.AdvertiserMetricsService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
public class AdvertiserMetricsController {

    private final AdvertiserMetricsService metricsService;
    private final pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard;

    public AdvertiserMetricsController(AdvertiserMetricsService metricsService,
                                      pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard) {
        this.metricsService = metricsService;
        this.planGuard = planGuard;
    }

    @GetMapping("/api/advertiser/metrics")
    public ResponseEntity<?> getMetrics(
            @RequestParam(required = false) String source,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var data = metricsService.getMetrics(advertiserId, source);
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/api/advertiser/metrics/by-agent")
    public ResponseEntity<?> getAgentMetrics(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var items = metricsService.getAgentMetrics(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @GetMapping("/api/advertiser/listings/metrics")
    public ResponseEntity<?> getListingMetrics(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var items = metricsService.getListingMetrics(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @GetMapping("/api/advertiser/pulse")
    public ResponseEntity<?> getPulse(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        // Radar comercial (pulse) é Pro+ — os restantes /metrics do dashboard são de todos os planos.
        planGuard.requireProFeatures(advertiserId);
        var data = metricsService.getPulse(advertiserId);
        return ResponseEntity.ok(Map.of("data", data));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
