package pt.properia.api.modules.auth.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

@RestController
@RequestMapping("/api/advertisers/onboarding")
public class AdvertiserOnboardingController {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AdvertiserOnboardingController(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getOnboarding(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var data = loadOnboarding(claims.userId());
        if (data == null) return ResponseEntity.ok(Map.of("data", (Object) null));

        // Append latest moderation decision
        var advertiserId = (String) data.get("advertiserId");
        var moderation = advertiserId != null ? loadLatestModeration(advertiserId) : null;
        var result = new LinkedHashMap<>(data);
        result.put("moderationSummary", moderation);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PatchMapping("/me")
    public ResponseEntity<?> updateOnboarding(@RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var adv = loadOnboarding(claims.userId());
        if (adv == null) throw new DomainException("NOT_FOUND", "Onboarding não iniciado.", 404);

        var advertiserId = UUID.fromString((String) adv.get("advertiserId"));
        var sets = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        params.put("id", advertiserId);

        if (body.containsKey("brandName")) { sets.add("brand_name = :brandName"); params.put("brandName", body.get("brandName")); }
        if (body.containsKey("website")) { sets.add("website = :website"); params.put("website", body.get("website")); }
        if (body.containsKey("phone")) { sets.add("phone = :phone"); params.put("phone", body.get("phone")); }
        if (body.containsKey("description")) { sets.add("description = :description"); params.put("description", body.get("description")); }
        if (body.containsKey("advertiserType")) { sets.add("advertiser_type = :advertiserType::properia.advertiser_type"); params.put("advertiserType", body.get("advertiserType")); }
        if (body.containsKey("licenseNumber")) { sets.add("license_number = :licenseNumber"); params.put("licenseNumber", body.get("licenseNumber")); }
        if (body.containsKey("logoUrl")) { sets.add("logo_url = :logoUrl"); params.put("logoUrl", body.get("logoUrl")); }

        if (!sets.isEmpty()) {
            sets.add("updated_at = now()");
            var sql = "UPDATE properia.advertisers SET " + String.join(", ", sets) + " WHERE id = :id";
            var q = jdbc.sql(sql);
            for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
            q.update();
        }

        var result = loadOnboarding(claims.userId());
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PostMapping("/start")
    public ResponseEntity<?> startOnboarding(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);

        // Check if already has an advertiser
        var existing = jdbc.sql("""
                SELECT a.id FROM properia.advertisers a
                JOIN properia.advertiser_users au ON au.advertiser_id = a.id
                WHERE au.user_id = :uid AND au.membership_role = 'owner'
                LIMIT 1
                """).param("uid", claims.userId())
            .query((rs, n) -> rs.getString("id")).optional();
        if (existing.isPresent()) {
            throw new DomainException("CONFLICT", "Já tens um anunciante associado.", 409);
        }

        var advertiserType = body.getOrDefault("advertiserType", "private_owner").toString();
        var brandName = body.containsKey("brandName") ? body.get("brandName").toString() : claims.name();
        var id = UUID.randomUUID();
        var slug = generateSlug(brandName, id);

        jdbc.sql("""
                INSERT INTO properia.advertisers
                  (id, brand_name, legal_name, slug, advertiser_type, verification_status, is_active, created_at, updated_at)
                VALUES (:id, :name, :name, :slug, :type::properia.advertiser_type, 'pending_review', false, now(), now())
                """)
            .param("id", id).param("name", brandName).param("slug", slug)
            .param("type", advertiserType).update();

        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role, created_at)
                VALUES (:adv, :uid, 'owner', now())
                """)
            .param("adv", id).param("uid", claims.userId()).update();

        var result = loadOnboarding(claims.userId());
        return ResponseEntity.status(201).body(Map.of("data", result));
    }

    private Map<String, Object> loadOnboarding(UUID userId) {
        return jdbc.sql("""
                SELECT a.id, a.brand_name, a.slug, a.advertiser_type, a.verification_status,
                       a.is_active, a.license_number, a.phone, a.website, a.description, a.logo_url,
                       a.created_at, au.membership_role
                FROM properia.advertisers a
                JOIN properia.advertiser_users au ON au.advertiser_id = a.id
                WHERE au.user_id = :uid AND au.membership_role = 'owner'
                ORDER BY a.created_at DESC LIMIT 1
                """).param("uid", userId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("advertiserId", rs.getString("id"));
                m.put("brandName", rs.getString("brand_name"));
                m.put("slug", rs.getString("slug"));
                m.put("advertiserType", rs.getString("advertiser_type"));
                m.put("verificationStatus", rs.getString("verification_status"));
                m.put("isActive", rs.getBoolean("is_active"));
                m.put("licenseNumber", rs.getString("license_number"));
                m.put("phone", rs.getString("phone"));
                m.put("website", rs.getString("website"));
                m.put("description", rs.getString("description"));
                m.put("logoUrl", rs.getString("logo_url"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("status", "active");
                return (Map<String, Object>) m;
            }).optional().orElse(null);
    }

    private Object loadLatestModeration(String advertiserId) {
        return jdbc.sql("""
                SELECT decision, reason_category, public_reason, decided_at
                FROM properia.moderation_decisions
                WHERE target_id = :id AND target_type = 'advertiser'
                ORDER BY decided_at DESC LIMIT 1
                """).param("id", UUID.fromString(advertiserId))
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("decision", rs.getString("decision"));
                m.put("reasonCategory", rs.getString("reason_category"));
                m.put("publicReason", rs.getString("public_reason"));
                m.put("decidedAt", rs.getTimestamp("decided_at") != null
                    ? rs.getTimestamp("decided_at").toInstant().toString() : null);
                return (Object) m;
            }).optional().orElse(null);
    }

    private String generateSlug(String brandName, UUID id) {
        var base = brandName.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        if (base.isBlank()) base = "anunciante";
        return base + "-" + id.toString().substring(0, 8);
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }
}
