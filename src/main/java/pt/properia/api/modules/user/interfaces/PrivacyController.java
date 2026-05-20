package pt.properia.api.modules.user.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.user.application.UserService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

@RestController
public class PrivacyController {

    private final UserService userService;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PrivacyController(UserService userService, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.userService = userService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Privacy preferences ────────────────────────────────────────────────────

    @GetMapping("/api/privacy/preferences")
    public ResponseEntity<?> getPrivacyPreferences(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var profile = userService.getProfile(claims.userId());
        var prefs = profile.preferences();
        var data = new LinkedHashMap<String, Object>();
        data.put("marketingConsent", Boolean.TRUE.equals(prefs.get("marketingEnabled")));
        data.put("personalizationConsent", Boolean.TRUE.equals(prefs.get("personalizationEnabled")));
        data.put("cookiePreferences", prefs.getOrDefault("cookiePreferences", Map.of()));
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PatchMapping("/api/privacy/preferences")
    public ResponseEntity<?> updatePrivacyPreferences(@RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal JwtClaims claims,
                                                      HttpServletRequest request) {
        requireAuth(claims);
        var profile = userService.getProfile(claims.userId());
        var prefs = new LinkedHashMap<>(profile.preferences());

        if (body.containsKey("marketingConsent")) {
            prefs.put("marketingEnabled", body.get("marketingConsent"));
        }
        if (body.containsKey("personalizationConsent")) {
            prefs.put("personalizationEnabled", body.get("personalizationConsent"));
        }
        if (body.containsKey("cookiePreferences")) {
            prefs.put("cookiePreferences", body.get("cookiePreferences"));
        }

        jdbc.sql("UPDATE properia.app_users SET preferences = :prefs::jsonb, updated_at = now() WHERE id = :uid")
            .param("prefs", toJson(prefs)).param("uid", claims.userId()).update();

        // Record consent event
        try {
            var ip = getClientIp(request);
            var ua = request.getHeader("User-Agent");
            jdbc.sql("""
                    INSERT INTO properia.consent_events
                      (id, user_id, email, purpose, granted, text_version, source, ip_address, user_agent, created_at)
                    VALUES (:id, :uid, :email, :purpose, :granted, '1.0', :source, :ip, :ua, now())
                    """)
                .param("id", UUID.randomUUID())
                .param("uid", claims.userId())
                .param("email", claims.email())
                .param("purpose", "marketing")
                .param("granted", Boolean.TRUE.equals(body.get("marketingConsent")))
                .param("source", "privacy_center")
                .param("ip", ip)
                .param("ua", ua != null ? ua.substring(0, Math.min(500, ua.length())) : null)
                .update();
        } catch (Exception ignored) {}

        var data = new LinkedHashMap<String, Object>();
        data.put("marketingConsent", Boolean.TRUE.equals(prefs.get("marketingEnabled")));
        data.put("personalizationConsent", Boolean.TRUE.equals(prefs.get("personalizationEnabled")));
        return ResponseEntity.ok(Map.of("data", data));
    }

    // ── Data requests ──────────────────────────────────────────────────────────

    @GetMapping("/api/privacy/data-requests")
    public ResponseEntity<?> getDataRequests(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = jdbc.sql("""
                SELECT id, request_type, status, created_at, completed_at
                FROM properia.privacy_data_requests WHERE user_id = :uid
                ORDER BY created_at DESC
                """).param("uid", claims.userId())
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("requestType", rs.getString("request_type"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("completedAt", rs.getTimestamp("completed_at") != null
                    ? rs.getTimestamp("completed_at").toInstant().toString() : null);
                return (Map<String, Object>) m;
            }).list();
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping("/api/privacy/data-requests")
    public ResponseEntity<?> createDataRequest(@RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var requestType = body.getOrDefault("requestType", "export").toString();
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.privacy_data_requests (id, user_id, request_type, status, created_at, updated_at)
                VALUES (:id, :uid, :type, 'pending', now(), now())
                """).param("id", id).param("uid", claims.userId()).param("type", requestType).update();
        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", id.toString(), "requestType", requestType, "status", "pending")));
    }

    // ── Decision dossiers ──────────────────────────────────────────────────────

    @GetMapping("/api/decision-dossiers")
    public ResponseEntity<?> getDecisionDossier(@RequestParam String listingId,
                                                @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var dossier = jdbc.sql("""
                SELECT id, listing_id, user_id, goal, budget_comfort, has_down_payment,
                       visit_soon, priority, must_haves::text, fit_label, strengths::text,
                       attention_points::text, visit_checklist::text, pricing::text,
                       share_with_advertiser, metadata::text, created_at, updated_at
                FROM properia.decision_dossiers
                WHERE user_id = :uid AND listing_id = :lid
                ORDER BY updated_at DESC LIMIT 1
                """)
            .param("uid", claims.userId())
            .param("lid", UUID.fromString(listingId))
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("listingId", rs.getString("listing_id"));
                m.put("userId", rs.getString("user_id"));
                m.put("goal", rs.getString("goal"));
                m.put("budgetComfort", rs.getInt("budget_comfort"));
                m.put("hasDownPayment", rs.getBoolean("has_down_payment"));
                m.put("visitSoon", rs.getBoolean("visit_soon"));
                m.put("priority", rs.getString("priority"));
                m.put("mustHaves", parseJsonArray(rs.getString("must_haves")));
                m.put("fitLabel", rs.getString("fit_label"));
                m.put("strengths", parseJsonArray(rs.getString("strengths")));
                m.put("attentionPoints", parseJsonArray(rs.getString("attention_points")));
                m.put("visitChecklist", parseJsonArray(rs.getString("visit_checklist")));
                m.put("pricing", parseJson(rs.getString("pricing")));
                m.put("shareWithAdvertiser", rs.getBoolean("share_with_advertiser"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).optional().orElse(null);

        return ResponseEntity.ok(Map.of("data", Map.of("dossier", dossier != null ? dossier : (Object) null)));
    }

    @PostMapping("/api/decision-dossiers")
    public ResponseEntity<?> saveDecisionDossier(@RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var listingId = UUID.fromString(body.get("listingId").toString());

        // Verify listing exists
        jdbc.sql("SELECT 1 FROM properia.listings WHERE id = :id AND status = 'published'")
            .param("id", listingId).query((rs, n) -> 1).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Imóvel não encontrado.", 404));

        var existing = jdbc.sql("""
                SELECT id FROM properia.decision_dossiers WHERE user_id = :uid AND listing_id = :lid
                ORDER BY updated_at DESC LIMIT 1
                """).param("uid", claims.userId()).param("lid", listingId)
            .query((rs, n) -> rs.getString("id")).optional();

        var mustHaves = toJson(body.getOrDefault("mustHaves", List.of()));
        var strengths = toJson(body.getOrDefault("strengths", List.of()));
        var attentionPoints = toJson(body.getOrDefault("attentionPoints", List.of()));
        var visitChecklist = toJson(body.getOrDefault("visitChecklist", List.of()));
        var pricing = toJson(body.getOrDefault("pricing", Map.of()));

        int statusCode;
        String id;
        if (existing.isPresent()) {
            id = existing.get();
            jdbc.sql("""
                    UPDATE properia.decision_dossiers SET goal = :goal, budget_comfort = :bc,
                      has_down_payment = :hdp, visit_soon = :vs, priority = :pri,
                      must_haves = :mh::jsonb, fit_label = :fl, strengths = :st::jsonb,
                      attention_points = :ap::jsonb, visit_checklist = :vc::jsonb,
                      pricing = :pricing::jsonb, share_with_advertiser = :share, updated_at = now()
                    WHERE id = :id
                    """)
                .param("id", UUID.fromString(id))
                .param("goal", body.getOrDefault("goal", "morar"))
                .param("bc", body.getOrDefault("budgetComfort", 0))
                .param("hdp", Boolean.TRUE.equals(body.get("hasDownPayment")))
                .param("vs", Boolean.TRUE.equals(body.get("visitSoon")))
                .param("pri", body.getOrDefault("priority", "localizacao"))
                .param("mh", mustHaves).param("fl", body.getOrDefault("fitLabel", "Precisa de validação"))
                .param("st", strengths).param("ap", attentionPoints).param("vc", visitChecklist)
                .param("pricing", pricing)
                .param("share", Boolean.TRUE.equals(body.get("shareWithAdvertiser")))
                .update();
            statusCode = 200;
        } else {
            id = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO properia.decision_dossiers
                      (id, listing_id, user_id, goal, budget_comfort, has_down_payment, visit_soon,
                       priority, must_haves, fit_label, strengths, attention_points, visit_checklist,
                       pricing, share_with_advertiser, created_at, updated_at)
                    VALUES (:id, :lid, :uid, :goal, :bc, :hdp, :vs, :pri,
                            :mh::jsonb, :fl, :st::jsonb, :ap::jsonb, :vc::jsonb,
                            :pricing::jsonb, :share, now(), now())
                    """)
                .param("id", UUID.fromString(id)).param("lid", listingId).param("uid", claims.userId())
                .param("goal", body.getOrDefault("goal", "morar"))
                .param("bc", body.getOrDefault("budgetComfort", 0))
                .param("hdp", Boolean.TRUE.equals(body.get("hasDownPayment")))
                .param("vs", Boolean.TRUE.equals(body.get("visitSoon")))
                .param("pri", body.getOrDefault("priority", "localizacao"))
                .param("mh", mustHaves).param("fl", body.getOrDefault("fitLabel", "Precisa de validação"))
                .param("st", strengths).param("ap", attentionPoints).param("vc", visitChecklist)
                .param("pricing", pricing)
                .param("share", Boolean.TRUE.equals(body.get("shareWithAdvertiser")))
                .update();
            statusCode = 201;
        }

        return ResponseEntity.status(statusCode).body(Map.of("data", Map.of(
            "dossier", Map.of("id", id, "listingId", listingId.toString()))));
    }

    // ── Partner leads ──────────────────────────────────────────────────────────

    @PostMapping("/api/partner-leads")
    public ResponseEntity<?> createPartnerLead(@RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal JwtClaims claims,
                                               HttpServletRequest request) {
        requireAuth(claims);
        var productType = body.getOrDefault("productType", "mortgage").toString();
        var listingId = body.containsKey("listingId") ? body.get("listingId").toString() : null;
        var id = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO properia.partner_leads
                  (id, user_id, product_type, listing_id, status, metadata, created_at, updated_at)
                VALUES (:id, :uid, :type, :lid, 'pending', :meta::jsonb, now(), now())
                """)
            .param("id", id)
            .param("uid", claims.userId())
            .param("type", productType)
            .param("lid", listingId != null ? UUID.fromString(listingId) : null)
            .param("meta", toJson(body))
            .update();

        // Record consent event
        try {
            var ip = getClientIp(request);
            jdbc.sql("""
                    INSERT INTO properia.consent_events
                      (id, user_id, email, purpose, granted, text_version, source, ip_address, created_at)
                    VALUES (:id, :uid, :email, 'partner_data_sharing', true, '1.0', 'partner_lead_form', :ip, now())
                    """)
                .param("id", UUID.randomUUID()).param("uid", claims.userId())
                .param("email", claims.email()).param("ip", ip).update();
        } catch (Exception ignored) {}

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "partnerLeadId", id.toString(),
            "productType", productType,
            "status", "pending"
        )));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj != null ? obj : Map.of()); }
        catch (Exception e) { return "{}"; }
    }
}
