package pt.properia.api.modules.listings.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.listings.application.VirtualTourService;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
public class VirtualTourController {

    private final VirtualTourService service;

    public VirtualTourController(VirtualTourService service) {
        this.service = service;
    }

    // ── Advertiser: trigger generation ──────────────────────────────────────────

    @PostMapping("/api/advertiser/listings/{id}/virtual-tour/generate")
    public ResponseEntity<?> generate(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {

        var advertiserId = resolveAdvertiserId(claims);
        service.requestGeneration(id, advertiserId);
        return ResponseEntity.accepted().body(Map.of(
            "data", Map.of("status", "pending", "message", "Geração do tour virtual iniciada.")
        ));
    }

    // ── Advertiser: poll status ──────────────────────────────────────────────────

    @GetMapping("/api/advertiser/listings/{id}/virtual-tour")
    public ResponseEntity<?> status(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {

        var advertiserId = resolveAdvertiserId(claims);
        var result = service.getStatus(id, advertiserId);
        return ResponseEntity.ok(Map.of("data", result));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private UUID resolveAdvertiserId(JwtClaims claims) {
        return claims.activeAdvertiserId() != null ? claims.activeAdvertiserId() : claims.userId();
    }
}
