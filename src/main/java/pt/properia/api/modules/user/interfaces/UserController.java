package pt.properia.api.modules.user.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.user.application.UserService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.util.*;

@RestController
public class UserController {

    private final UserService userService;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public UserController(UserService userService, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.userService = userService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Favoritos ──────────────────────────────────────────────────────────────

    @GetMapping("/api/favoritos")
    public ResponseEntity<?> getFavoritos(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var ids = userService.getFavoriteListingIds(claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("listingIds", ids)));
    }

    @PostMapping("/api/favoritos")
    public ResponseEntity<?> saveFavorito(@RequestBody Map<String, String> body,
                                          @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var listingId = body.get("listingId");
        if (listingId == null || listingId.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "listingId inválido.", 422);
        }
        var lid = UUID.fromString(listingId);
        // Verify listing exists and is published
        var exists = jdbc.sql("""
                SELECT 1 FROM properia.listings WHERE id = :id AND status = 'published' LIMIT 1
                """)
            .param("id", lid)
            .query((rs, n) -> rs.getInt(1))
            .optional()
            .isPresent();
        if (!exists) throw new DomainException("NOT_FOUND", "Imóvel não encontrado ou indisponível.", 404);
        userService.saveListing(claims.userId(), lid);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/favoritos/{listingId}")
    public ResponseEntity<?> removeFavorito(@PathVariable UUID listingId,
                                            @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        userService.removeSavedListing(claims.userId(), listingId);
        return ResponseEntity.noContent().build();
    }

    // ── Alertas ────────────────────────────────────────────────────────────────

    @GetMapping("/api/alertas")
    public ResponseEntity<?> getAlertas(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = userService.getAlertas(claims.userId());
        return ResponseEntity.ok(Map.of("data", items));
    }

    @PostMapping("/api/alertas")
    public ResponseEntity<?> createAlerta(@RequestBody Map<String, Object> body,
                                          @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        var params = body.get("params") instanceof Map<?,?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        var alerta = userService.createAlerta(claims.userId(), name, params);
        return ResponseEntity.status(201).body(Map.of("data", alerta));
    }

    @DeleteMapping("/api/alertas/{id}")
    public ResponseEntity<?> deleteAlerta(@PathVariable UUID id,
                                          @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        userService.deleteAlerta(claims.userId(), id);
        return ResponseEntity.noContent().build();
    }

    // ── Profile/me ─────────────────────────────────────────────────────────────

    @GetMapping("/api/profile/me")
    public ResponseEntity<?> getMyProfile(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var uid = claims.userId();

        var user = userService.getProfile(uid);

        // Visit stats
        var visitsTotal = jdbc.sql("""
                SELECT COUNT(*) FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE l.user_id = :uid
                """).param("uid", uid).query(Long.class).single();
        var visitsUpcoming = jdbc.sql("""
                SELECT COUNT(*) FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE l.user_id = :uid AND v.starts_at > now()
                """).param("uid", uid).query(Long.class).single();
        var visitsCompleted = jdbc.sql("""
                SELECT COUNT(*) FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE l.user_id = :uid AND v.status = 'completed'
                """).param("uid", uid).query(Long.class).single();

        // Memberships
        var memberships = jdbc.sql("""
                SELECT au.advertiser_id, a.brand_name, a.slug, au.membership_role
                FROM properia.advertiser_users au
                JOIN properia.advertisers a ON a.id = au.advertiser_id
                WHERE au.user_id = :uid
                """).param("uid", uid)
            .query((rs, n) -> Map.of(
                "advertiserId", rs.getString("advertiser_id"),
                "advertiserName", Optional.ofNullable(rs.getString("brand_name")).orElse("Anunciante"),
                "advertiserSlug", Optional.ofNullable(rs.getString("slug")).orElse(""),
                "membershipRole", rs.getString("membership_role")
            )).list();

        var prefs = user.preferences();
        var marketingEnabled = Boolean.TRUE.equals(prefs.get("marketingEnabled"));
        var personalizationEnabled = Boolean.TRUE.equals(prefs.get("personalizationEnabled"));

        var userMap = new LinkedHashMap<String, Object>();
        userMap.put("id", user.id().toString());
        userMap.put("name", Optional.ofNullable(user.name()).orElse(""));
        userMap.put("email", Optional.ofNullable(user.email()).orElse(""));
        userMap.put("role", "user");
        userMap.put("avatarUrl", Optional.ofNullable(user.avatarUrl()).orElse(""));
        userMap.put("createdAt", user.createdAt().toString());
        userMap.put("lastLoginAt", null);
        userMap.put("emailVerifiedAt", getEmailVerifiedAt(uid));

        var data = new LinkedHashMap<String, Object>();
        data.put("user", userMap);
        data.put("stats", Map.of(
            "visitsTotal", visitsTotal,
            "visitsUpcoming", visitsUpcoming,
            "visitsCompleted", visitsCompleted,
            "advertiserMemberships", memberships.size()
        ));
        data.put("privacy", Map.of(
            "marketingEnabled", marketingEnabled,
            "personalizationEnabled", personalizationEnabled
        ));
        data.put("memberships", memberships);

        return ResponseEntity.ok(Map.of("data", data));
    }

    // ── Commute destinations ────────────────────────────────────────────────────

    @GetMapping("/api/profile/me/commute-destinations")
    public ResponseEntity<?> getCommuteDestinations(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = userService.getCommuteDestinations(claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PutMapping("/api/profile/me/commute-destinations")
    public ResponseEntity<?> saveCommuteDestination(@RequestBody Map<String, Object> body,
                                                    @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = userService.saveCommuteDestination(claims.userId(), new LinkedHashMap<>(body));
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    // ── Custom Profiles ────────────────────────────────────────────────────────

    @GetMapping("/api/profiles")
    public ResponseEntity<?> listCustomProfiles(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = listProfiles(claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping("/api/profiles")
    public ResponseEntity<?> createCustomProfile(@RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var item = upsertProfile(claims.userId(), null, body);
        return ResponseEntity.status(201).body(Map.of("data", Map.of("item", item)));
    }

    @PatchMapping("/api/profiles/{id}")
    public ResponseEntity<?> updateCustomProfile(@PathVariable UUID id,
                                                 @RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var item = upsertProfile(claims.userId(), id, body);
        return ResponseEntity.ok(Map.of("data", Map.of("item", item)));
    }

    @DeleteMapping("/api/profiles/{id}")
    public ResponseEntity<?> deleteCustomProfile(@PathVariable UUID id,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var updated = jdbc.sql("""
                UPDATE properia.profiles SET is_active = false, updated_at = now()
                WHERE id = :id AND owner_user_id = :uid AND profile_type = 'custom'
                """).param("id", id).param("uid", claims.userId()).update();
        if (updated == 0) throw new DomainException("NOT_FOUND", "Perfil não encontrado.", 404);
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> listProfiles(UUID userId) {
        return jdbc.sql("""
                SELECT id, name, description, color, icon, created_at, rules_json::text
                FROM properia.profiles
                WHERE owner_user_id = :uid AND profile_type = 'custom' AND is_active = true
                ORDER BY created_at DESC
                """).param("uid", userId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("name", rs.getString("name"));
                m.put("description", Optional.ofNullable(rs.getString("description")).orElse(""));
                m.put("color", Optional.ofNullable(rs.getString("color")).orElse("#c4622d"));
                m.put("icon", Optional.ofNullable(rs.getString("icon")).orElse("Sparkles"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("rules", parseJson(rs.getString("rules_json")));
                return (Map<String, Object>) m;
            }).list();
    }

    private Map<String, Object> upsertProfile(UUID userId, UUID profileId, Map<String, Object> body) {
        var name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new DomainException("VALIDATION_ERROR", "Nome é obrigatório.", 422);
        var rulesJson = toJson(body);
        var color = body.getOrDefault("color", "#c4622d").toString();
        var icon = body.getOrDefault("icon", "Sparkles").toString();

        if (profileId == null) {
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO properia.profiles
                      (id, owner_user_id, profile_type, name, description, color, icon, is_system, is_active, rules_json, created_at, updated_at)
                    VALUES (:id, :uid, 'custom', :name, :desc, :color, :icon, false, true, :rules::jsonb, now(), now())
                    """)
                .param("id", id).param("uid", userId).param("name", name.trim())
                .param("desc", buildProfileDesc(body))
                .param("color", color).param("icon", icon).param("rules", rulesJson).update();
            return listProfiles(userId).stream()
                .filter(p -> id.toString().equals(p.get("id"))).findFirst()
                .orElseThrow(() -> new DomainException("INTERNAL_ERROR", "Erro ao criar perfil.", 500));
        } else {
            var updated = jdbc.sql("""
                    UPDATE properia.profiles SET name = :name, description = :desc, color = :color,
                      icon = :icon, rules_json = :rules::jsonb, updated_at = now()
                    WHERE id = :id AND owner_user_id = :uid AND profile_type = 'custom' AND is_active = true
                    """)
                .param("id", profileId).param("uid", userId).param("name", name.trim())
                .param("desc", buildProfileDesc(body))
                .param("color", color).param("icon", icon).param("rules", rulesJson).update();
            if (updated == 0) throw new DomainException("NOT_FOUND", "Perfil não encontrado.", 404);
            return listProfiles(userId).stream()
                .filter(p -> profileId.toString().equals(p.get("id"))).findFirst()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Perfil não encontrado.", 404));
        }
    }

    private String buildProfileDesc(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var features = body.get("features") instanceof List<?> l ? l.size() : 0;
        @SuppressWarnings("unchecked")
        var estilos = body.get("estilosPreferidos") instanceof List<?> l ? l.size() : 0;
        @SuppressWarnings("unchecked")
        var pois = body.get("poisImportantes") instanceof List<?> l ? l.size() : 0;
        return "Perfil personalizado · " + (features + estilos) + " sinais de imovel · " + pois + " POIs";
    }

    private String getEmailVerifiedAt(UUID userId) {
        return jdbc.sql("SELECT email_verified_at FROM properia.app_users WHERE id = :uid")
            .param("uid", userId)
            .query((rs, n) -> {
                var ts = rs.getTimestamp("email_verified_at");
                return ts != null ? ts.toInstant().toString() : null;
            })
            .optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of()); }
        catch (Exception e) { return "{}"; }
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }
}
