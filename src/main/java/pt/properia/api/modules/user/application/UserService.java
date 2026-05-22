package pt.properia.api.modules.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class UserService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public UserService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Favoritos ─────────────────────────────────────────────────────────────

    public List<UUID> getFavoriteListingIds(UUID userId) {
        return jdbc.sql("""
                SELECT listing_id FROM properia.saved_listings
                WHERE user_id = :uid ORDER BY created_at DESC
                """)
            .param("uid", userId)
            .query((rs, n) -> UUID.fromString(rs.getString("listing_id")))
            .list();
    }

    public void saveListing(UUID userId, UUID listingId) {
        jdbc.sql("""
                INSERT INTO properia.saved_listings (user_id, listing_id, created_at, updated_at)
                VALUES (:uid, :lid, now(), now())
                ON CONFLICT (user_id, listing_id) DO NOTHING
                """)
            .param("uid", userId)
            .param("lid", listingId)
            .update();
    }

    public void removeSavedListing(UUID userId, UUID listingId) {
        jdbc.sql("""
                DELETE FROM properia.saved_listings WHERE user_id = :uid AND listing_id = :lid
                """)
            .param("uid", userId)
            .param("lid", listingId)
            .update();
    }

    // ── Alertas (saved searches) ──────────────────────────────────────────────

    public record AlertaDto(UUID id, String name, Map<String, Object> params,
                            boolean isActive, Instant createdAt, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public List<AlertaDto> getAlertas(UUID userId) {
        return jdbc.sql("""
                SELECT id, name, params::text, is_active, created_at, updated_at
                FROM properia.saved_searches
                WHERE user_id = :uid AND is_active = true
                ORDER BY created_at DESC LIMIT 20
                """)
            .param("uid", userId)
            .query((rs, n) -> new AlertaDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                parseJson(rs.getString("params")),
                rs.getBoolean("is_active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .list();
    }

    public AlertaDto createAlerta(UUID userId, String name, Map<String, Object> params) {
        if (name == null || name.isBlank()) throw new DomainException("BAD_REQUEST", "Nome é obrigatório.", 400);

        var count = jdbc.sql("SELECT COUNT(*) FROM properia.saved_searches WHERE user_id = :uid AND is_active = true")
            .param("uid", userId).query(Long.class).single();
        if (count >= 20) throw new DomainException("CONFLICT", "Limite de 20 alertas atingido.", 409);

        var id = UUID.randomUUID();
        var paramsJson = toJson(params);
        var now = Instant.now();
        jdbc.sql("""
                INSERT INTO properia.saved_searches (id, user_id, name, params, is_active, created_at, updated_at)
                VALUES (:id, :uid, :name, :params::jsonb, true, :now, :now)
                """)
            .param("id", id).param("uid", userId).param("name", name)
            .param("params", paramsJson).param("now", java.sql.Timestamp.from(now)).update();

        return new AlertaDto(id, name, params, true, now, now);
    }

    public void deleteAlerta(UUID userId, UUID alertaId) {
        var deleted = jdbc.sql("""
                UPDATE properia.saved_searches SET is_active = false, updated_at = now()
                WHERE id = :id AND user_id = :uid
                """)
            .param("id", alertaId).param("uid", userId).update();
        if (deleted == 0) throw new DomainException("NOT_FOUND", "Alerta não encontrado.", 404);
    }

    // ── User Profile ──────────────────────────────────────────────────────────

    public record ProfileDto(UUID id, String name, String email, String avatarUrl,
                             String phone, Map<String, Object> preferences, Instant createdAt) {}

    @Transactional(readOnly = true)
    public ProfileDto getProfile(UUID userId) {
        return jdbc.sql("""
                SELECT id, full_name, email, avatar_url, phone, preferences::text, created_at
                FROM properia.app_users WHERE id = :uid
                """)
            .param("uid", userId)
            .query((rs, n) -> new ProfileDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("avatar_url"),
                rs.getString("phone"),
                parseJson(rs.getString("preferences")),
                rs.getTimestamp("created_at").toInstant()
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Perfil não encontrado.", 404));
    }

    public ProfileDto updateProfile(UUID userId, Map<String, Object> input) {
        if (input.containsKey("name")) {
            jdbc.sql("UPDATE properia.app_users SET full_name = :name, updated_at = now() WHERE id = :uid")
                .param("name", input.get("name")).param("uid", userId).update();
        }
        if (input.containsKey("phone")) {
            jdbc.sql("UPDATE properia.app_users SET phone = :phone, updated_at = now() WHERE id = :uid")
                .param("phone", input.get("phone")).param("uid", userId).update();
        }
        if (input.containsKey("avatarUrl")) {
            jdbc.sql("UPDATE properia.app_users SET avatar_url = :url, updated_at = now() WHERE id = :uid")
                .param("url", input.get("avatarUrl")).param("uid", userId).update();
        }
        return getProfile(userId);
    }

    // ── Commute destinations (stored in app_users.preferences JSONB) ──────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCommuteDestinations(UUID userId) {
        var prefs = getProfile(userId).preferences();
        var destinations = prefs.get("commuteDestinations");
        if (destinations instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> saveCommuteDestination(UUID userId, Map<String, Object> destination) {
        var profile = getProfile(userId);
        var prefs = new LinkedHashMap<>(profile.preferences());
        var current = new ArrayList<Map<String, Object>>();
        if (prefs.get("commuteDestinations") instanceof List<?> list) {
            current.addAll((List<Map<String, Object>>) list);
        }
        destination.put("id", UUID.randomUUID().toString());
        destination.put("savedAt", Instant.now().toString());
        current.add(destination);
        prefs.put("commuteDestinations", current);
        savePreferences(userId, prefs);
        return current;
    }

    @SuppressWarnings("unchecked")
    public void deleteCommuteDestination(UUID userId, String destinationId) {
        var profile = getProfile(userId);
        var prefs = new LinkedHashMap<>(profile.preferences());
        var current = new ArrayList<Map<String, Object>>();
        if (prefs.get("commuteDestinations") instanceof List<?> list) {
            current.addAll((List<Map<String, Object>>) list);
        }
        current.removeIf(d -> destinationId.equals(d.get("id")));
        prefs.put("commuteDestinations", current);
        savePreferences(userId, prefs);
    }

    private void savePreferences(UUID userId, Map<String, Object> prefs) {
        jdbc.sql("UPDATE properia.app_users SET preferences = :prefs::jsonb, updated_at = now() WHERE id = :uid")
            .param("prefs", toJson(prefs)).param("uid", userId).update();
    }

    // ── Public profiles ───────────────────────────────────────────────────────

    public record PublicProfileDto(UUID id, String name, String avatarUrl) {}

    @Transactional(readOnly = true)
    public PublicProfileDto getPublicProfile(UUID userId) {
        return jdbc.sql("SELECT id, full_name, avatar_url FROM properia.app_users WHERE id = :uid")
            .param("uid", userId)
            .query((rs, n) -> new PublicProfileDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("full_name"),
                rs.getString("avatar_url")
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Utilizador não encontrado.", 404));
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
}
