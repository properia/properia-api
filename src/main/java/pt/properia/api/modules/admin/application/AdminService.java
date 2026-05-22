package pt.properia.api.modules.admin.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class AdminService {

    private final JdbcClient jdbc;

    public AdminService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Listing moderation ────────────────────────────────────────────────────

    public record ModerationListingItem(String id, String publicId, String title, String status,
                                        String moderationStatus, String advertiserId,
                                        String advertiserName, Instant createdAt, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public List<ModerationListingItem> listPendingListings() {
        return jdbc.sql("""
                SELECT l.id, l.public_id, l.title, l.status, l.moderation_status,
                       l.advertiser_id, a.brand_name, l.created_at, l.updated_at
                FROM properia.listings l
                JOIN properia.advertisers a ON a.id = l.advertiser_id
                WHERE l.moderation_status = 'pending_review'
                ORDER BY l.created_at
                """)
            .query((rs, n) -> new ModerationListingItem(
                rs.getString("id"),
                rs.getString("public_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("moderation_status"),
                rs.getString("advertiser_id"),
                rs.getString("brand_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .list();
    }

    public void moderateListing(UUID listingId, String decision, String reason) {
        var valid = Set.of("approved", "rejected", "flagged");
        if (!valid.contains(decision)) throw new DomainException("BAD_REQUEST", "Decisão inválida.", 400);

        var moderationStatus = "approved".equals(decision) ? "approved" : "rejected";
        var listingStatus = "approved".equals(decision) ? "active" : "rejected";

        var updated = jdbc.sql("""
                UPDATE properia.listings
                SET moderation_status = :ms,
                    status = :ls,
                    moderation_note = :reason,
                    updated_at = now()
                WHERE id = :id
                """)
            .param("ms", moderationStatus)
            .param("ls", listingStatus)
            .param("reason", reason)
            .param("id", listingId)
            .update();

        if (updated == 0) throw new DomainException("NOT_FOUND", "Anúncio não encontrado.", 404);
    }

    // ── Advertiser moderation ─────────────────────────────────────────────────

    public record ModerationAdvertiserItem(String id, String brandName, String legalName,
                                           String planCode, String status, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<ModerationAdvertiserItem> listAdvertisers() {
        return jdbc.sql("""
                SELECT id, brand_name, legal_name, plan_code, status, created_at
                FROM properia.advertisers
                WHERE status = 'pending_review' OR status = 'active'
                ORDER BY created_at DESC
                LIMIT 100
                """)
            .query((rs, n) -> new ModerationAdvertiserItem(
                rs.getString("id"),
                rs.getString("brand_name"),
                rs.getString("legal_name"),
                rs.getString("plan_code"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
            ))
            .list();
    }

    public void updateAdvertiserStatus(UUID advertiserId, String status) {
        var valid = Set.of("active", "suspended", "pending_review", "rejected");
        if (!valid.contains(status)) throw new DomainException("BAD_REQUEST", "Status inválido.", 400);

        var updated = jdbc.sql("""
                UPDATE properia.advertisers SET status = :status, updated_at = now()
                WHERE id = :id
                """)
            .param("status", status)
            .param("id", advertiserId)
            .update();

        if (updated == 0) throw new DomainException("NOT_FOUND", "Anunciante não encontrado.", 404);
    }

    public void activatePilot(UUID advertiserId) {
        jdbc.sql("""
                UPDATE properia.advertisers
                SET plan_code = 'pilot', updated_at = now()
                WHERE id = :id
                """)
            .param("id", advertiserId)
            .update();
    }

    // ── Audit events ──────────────────────────────────────────────────────────

    public record AuditEventDto(UUID id, String eventCategory, String action, String severity,
                                UUID actorUserId, String actorEmail, UUID advertiserId,
                                String entityType, UUID entityId, String ipAddress,
                                Map<String, Object> metadata, Instant createdAt) {}

    @Transactional(readOnly = true)
    public Map<String, Object> queryAuditEvents(String category, String severity, String actorUserId,
                                                 String advertiserId, String action,
                                                 String from, String to, int limit, int offset) {
        var where = new StringBuilder("WHERE 1=1");
        var params = new HashMap<String, Object>();

        if (category != null) { where.append(" AND event_category = :cat"); params.put("cat", category); }
        if (severity != null) { where.append(" AND severity = :sev"); params.put("sev", severity); }
        if (actorUserId != null) { where.append(" AND actor_user_id = :actor::uuid"); params.put("actor", actorUserId); }
        if (advertiserId != null) { where.append(" AND advertiser_id = :adv::uuid"); params.put("adv", advertiserId); }
        if (action != null) { where.append(" AND action LIKE :action"); params.put("action", action + "%"); }
        if (from != null) { where.append(" AND created_at >= :from"); params.put("from", java.sql.Timestamp.from(Instant.parse(from))); }
        if (to != null) { where.append(" AND created_at <= :to"); params.put("to", java.sql.Timestamp.from(Instant.parse(to))); }

        var sql = "SELECT id, event_category, action, severity, actor_user_id, actor_email, " +
                  "advertiser_id, entity_type, entity_id, ip_address, metadata::text, created_at " +
                  "FROM properia.system_audit_events " + where + " ORDER BY created_at DESC LIMIT :lim OFFSET :off";

        params.put("lim", limit);
        params.put("off", offset);

        var q = jdbc.sql(sql);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());

        var events = q.query((rs, n) -> Map.of(
            "id", rs.getString("id"),
            "eventCategory", rs.getString("event_category"),
            "action", rs.getString("action"),
            "severity", String.valueOf(rs.getObject("severity")),
            "actorEmail", String.valueOf(rs.getObject("actor_email")),
            "advertiserId", String.valueOf(rs.getObject("advertiser_id")),
            "entityType", String.valueOf(rs.getObject("entity_type")),
            "createdAt", rs.getTimestamp("created_at").toInstant().toString()
        )).list();

        var countSql = "SELECT COUNT(*) FROM properia.system_audit_events " + where;
        var cq = jdbc.sql(countSql);
        for (var e : params.entrySet()) {
            if (!e.getKey().equals("lim") && !e.getKey().equals("off")) cq = cq.param(e.getKey(), e.getValue());
        }
        var total = cq.query(Long.class).single();

        return Map.of("events", events, "total", total, "limit", limit, "offset", offset);
    }

    public Map<String, Object> getAuditStats() {
        var total = jdbc.sql("SELECT COUNT(*) FROM properia.system_audit_events").query(Long.class).single();
        var last24h = jdbc.sql("""
                SELECT COUNT(*) FROM properia.system_audit_events
                WHERE created_at > now() - interval '24 hours'
                """).query(Long.class).single();
        return Map.of("totalEvents", total, "last24h", last24h);
    }
}
