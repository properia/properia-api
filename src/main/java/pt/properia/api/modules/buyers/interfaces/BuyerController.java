package pt.properia.api.modules.buyers.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.buyers.application.BuyerService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/buyers")
public class BuyerController {

    private final BuyerService buyerService;

    public BuyerController(BuyerService buyerService) {
        this.buyerService = buyerService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignedToUserId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal JwtClaims claims) {
        var ctx = resolveContext(claims);
        UUID assignedTo = assignedToUserId != null ? UUID.fromString(assignedToUserId) : ctx.scopedUserId;
        String statusFilter = (status == null || status.isBlank() || "todos".equalsIgnoreCase(status) || "all".equalsIgnoreCase(status)) ? null : status;
        String qFilter = (q == null || q.isBlank()) ? null : q;
        var result = buyerService.listProfiles(ctx.advertiserId, statusFilter, assignedTo, qFilter, page, pageSize);
        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", result.items(),
            "total", result.total(),
            "page", result.page(),
            "pageSize", result.pageSize(),
            "totalPages", result.totalPages()
        )));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var ctx = resolveContext(claims);
        UUID assignedTo = ctx.scopedUserId != null ? ctx.scopedUserId : claims.userId();
        if (body.get("assignedToUserId") != null && ctx.scopedUserId == null) {
            assignedTo = UUID.fromString((String) body.get("assignedToUserId"));
        }
        var profile = buyerService.createProfile(ctx.advertiserId, assignedTo, body);
        return ResponseEntity.status(201).body(Map.of("data", profile));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var ctx = resolveContext(claims);
        var profile = buyerService.getProfile(ctx.advertiserId, id, ctx.scopedUserId);
        return ResponseEntity.ok(Map.of("data", profile));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var ctx = resolveContext(claims);
        var profile = buyerService.updateProfile(ctx.advertiserId, id, ctx.scopedUserId, body);
        return ResponseEntity.ok(Map.of("data", profile));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var ctx = resolveContext(claims);
        buyerService.deleteProfile(ctx.advertiserId, id, ctx.scopedUserId);
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    private record AccessContext(UUID advertiserId, UUID scopedUserId) {}

    private AccessContext resolveContext(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        // Sales role sees only their own leads
        UUID scopedUserId = "sales".equals(claims.role()) ? claims.userId() : null;
        return new AccessContext(claims.activeAdvertiserId(), scopedUserId);
    }
}
