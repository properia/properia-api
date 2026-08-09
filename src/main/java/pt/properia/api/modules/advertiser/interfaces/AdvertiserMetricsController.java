package pt.properia.api.modules.advertiser.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.advertiser.application.AdvertiserMetricsService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class AdvertiserMetricsController {

    private static final Set<String> ROLES_ALLOWED_AGENT_BREAKDOWN = Set.of("owner", "admin", "editor");

    private final AdvertiserMetricsService metricsService;
    private final pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard;
    private final JdbcClient jdbc;

    public AdvertiserMetricsController(AdvertiserMetricsService metricsService,
                                      pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard,
                                      JdbcClient jdbc) {
        this.metricsService = metricsService;
        this.planGuard = planGuard;
        this.jdbc = jdbc;
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
        // Quebra de performance por colega — não é para sales/viewer verem os números
        // uns dos outros, só owner/admin/editor.
        var role = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", claims.userId())
            .query(String.class).optional().orElse(null);
        if (!ROLES_ALLOWED_AGENT_BREAKDOWN.contains(role)) {
            throw new DomainException("FORBIDDEN", "Sem permissão para ver métricas de toda a equipa.", 403);
        }
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
