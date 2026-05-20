package pt.properia.api.modules.admin.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.admin.application.AdminService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ── Listing moderation ────────────────────────────────────────────────────

    @GetMapping("/moderation/listings")
    public ResponseEntity<?> listPendingListings(@AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        var items = adminService.listPendingListings();
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PatchMapping("/moderation/listings/{id}")
    public ResponseEntity<?> moderateListing(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        adminService.moderateListing(id, body.get("decision"), body.get("reason"));
        return ResponseEntity.ok(Map.of("data", Map.of("applied", true)));
    }

    // ── Advertiser moderation ─────────────────────────────────────────────────

    @GetMapping("/moderation/advertisers")
    public ResponseEntity<?> listAdvertisers(@AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        var items = adminService.listAdvertisers();
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PatchMapping("/moderation/advertisers/{id}")
    public ResponseEntity<?> updateAdvertiserStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        adminService.updateAdvertiserStatus(id, body.get("status"));
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    @PostMapping("/advertisers/{id}/pilot")
    public ResponseEntity<?> activatePilot(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        adminService.activatePilot(id);
        return ResponseEntity.ok(Map.of("data", Map.of("activated", true)));
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    @GetMapping("/audit/events")
    public ResponseEntity<?> auditEvents(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String advertiserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        var data = adminService.queryAuditEvents(category, severity, actorUserId, advertiserId,
            action, from, to, limit, offset);
        return ResponseEntity.ok(Map.of("data", data));
    }

    // ── Audit stats ───────────────────────────────────────────────────────────

    @GetMapping("/audit/stats")
    public ResponseEntity<?> auditStats(@AuthenticationPrincipal JwtClaims claims) {
        requireAdmin(claims);
        var stats = adminService.getAuditStats();
        return ResponseEntity.ok(Map.of("data", Map.of("audit", stats)));
    }

    private void requireAdmin(JwtClaims claims) {
        if (claims == null || !"admin".equals(claims.role())) {
            throw new DomainException("FORBIDDEN", "Acesso restrito a administradores.", 403);
        }
    }
}
