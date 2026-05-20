package pt.properia.api.modules.buyers.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/public")
public class PublicConsentController {

    private final JdbcClient jdbc;

    public PublicConsentController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Stats ──────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        var total = jdbc.sql("SELECT COUNT(*) FROM properia.listings WHERE status = 'published'")
            .query(Long.class).single();
        return ResponseEntity.ok(Map.of("data", Map.of("totalPublishedListings", total)));
    }

    // ── Buyer consent page ─────────────────────────────────────────────────────

    @GetMapping("/buyer-consent/{token}")
    public ResponseEntity<?> getConsentPage(@PathVariable UUID token) {
        var row = jdbc.sql("""
                SELECT bp.id, bp.name, bp.email, bp.consent_status, bp.consent_accepted_at,
                       bp.consent_expires_at, a.brand_name as advertiser_name
                FROM properia.buyer_profiles bp
                JOIN properia.advertisers a ON a.id = bp.advertiser_id
                WHERE bp.consent_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "buyerName", Optional.ofNullable(rs.getString("name")).orElse(""),
                "buyerEmail", Optional.ofNullable(rs.getString("email")).orElse(""),
                "consentStatus", rs.getString("consent_status"),
                "consentAcceptedAt", rs.getTimestamp("consent_accepted_at") != null
                    ? rs.getTimestamp("consent_accepted_at").toInstant().toString() : null,
                "consentExpiresAt", rs.getTimestamp("consent_expires_at") != null
                    ? rs.getTimestamp("consent_expires_at").toInstant().toString() : null,
                "advertiserName", Optional.ofNullable(rs.getString("advertiser_name")).orElse("")
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Link inválido ou expirado.", 404));
        return ResponseEntity.ok(Map.of("data", row));
    }

    @PostMapping("/buyer-consent/{token}")
    public ResponseEntity<?> acceptConsent(@PathVariable UUID token,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        var ip = getClientIp(request);
        var updated = jdbc.sql("""
                UPDATE properia.buyer_profiles
                SET consent_status = 'accepted'::properia.buyer_consent_status,
                    consent_accepted_at = now(),
                    consent_ip_address = :ip,
                    updated_at = now()
                WHERE consent_token = :token AND consent_status = 'pending'
                """)
            .param("token", token)
            .param("ip", ip)
            .update();
        if (updated == 0) {
            throw new DomainException("CONFLICT", "Este consentimento já foi aceite ou revogado.", 409);
        }
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    @PostMapping("/buyer-consent/{token}/revoke")
    public ResponseEntity<?> revokeConsent(@PathVariable UUID token) {
        jdbc.sql("""
                UPDATE properia.buyer_profiles
                SET consent_status = 'revoked'::properia.buyer_consent_status, updated_at = now()
                WHERE consent_token = :token
                """)
            .param("token", token)
            .update();
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    private String getClientIp(HttpServletRequest request) {
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
