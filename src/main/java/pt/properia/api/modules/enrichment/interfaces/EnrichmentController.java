package pt.properia.api.modules.enrichment.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

/**
 * Handles enrichment endpoints for listing enrichment (zone, POIs, vision, matching).
 * Job enqueueing triggers async processing; results are read from enrichment tables.
 */
@RestController
@RequestMapping("/api/enrichment/listings/{id}")
public class EnrichmentController {

    private final JdbcClient jdbc;

    public EnrichmentController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Zone enrichment ────────────────────────────────────────────────────────

    @GetMapping("/zone")
    public ResponseEntity<?> getZone(@PathVariable UUID id) {
        var data = loadEnrichment(id, "zone");
        return ResponseEntity.ok(Map.of("data", data != null ? data : (Object) null));
    }

    @PostMapping("/zone")
    public ResponseEntity<?> enqueueZone(@PathVariable UUID id,
                                         @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserOwner(claims, id);
        enqueueJob(id, "listing_zone_enrichment");
        return ResponseEntity.status(202).body(Map.of("data", Map.of("queued", true, "jobType", "listing_zone_enrichment")));
    }

    @GetMapping("/zone/status")
    public ResponseEntity<?> zoneStatus(@PathVariable UUID id) {
        var status = getJobStatus(id, "listing_zone_enrichment");
        return ResponseEntity.ok(Map.of("data", Map.of("status", status)));
    }

    // ── POIs enrichment ────────────────────────────────────────────────────────

    @GetMapping("/pois")
    public ResponseEntity<?> getPois(@PathVariable UUID id) {
        var data = loadEnrichment(id, "pois");
        return ResponseEntity.ok(Map.of("data", data != null ? data : (Object) null));
    }

    @PostMapping("/pois")
    public ResponseEntity<?> enqueuePois(@PathVariable UUID id,
                                         @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserOwner(claims, id);
        enqueueJob(id, "listing_pois_enrichment");
        return ResponseEntity.status(202).body(Map.of("data", Map.of("queued", true, "jobType", "listing_pois_enrichment")));
    }

    @GetMapping("/pois/status")
    public ResponseEntity<?> poisStatus(@PathVariable UUID id) {
        var status = getJobStatus(id, "listing_pois_enrichment");
        return ResponseEntity.ok(Map.of("data", Map.of("status", status)));
    }

    // ── Vision enrichment ──────────────────────────────────────────────────────

    @GetMapping("/vision")
    public ResponseEntity<?> getVision(@PathVariable UUID id) {
        var data = loadEnrichment(id, "vision");
        return ResponseEntity.ok(Map.of("data", data != null ? data : (Object) null));
    }

    @PostMapping("/vision")
    public ResponseEntity<?> enqueueVision(@PathVariable UUID id,
                                           @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserOwner(claims, id);
        enqueueJob(id, "listing_vision_enrichment");
        return ResponseEntity.status(202).body(Map.of("data", Map.of("queued", true, "jobType", "listing_vision_enrichment")));
    }

    @GetMapping("/vision/status")
    public ResponseEntity<?> visionStatus(@PathVariable UUID id) {
        var status = getJobStatus(id, "listing_vision_enrichment");
        return ResponseEntity.ok(Map.of("data", Map.of("status", status)));
    }

    // ── Matching enrichment ────────────────────────────────────────────────────

    @GetMapping("/matching")
    public ResponseEntity<?> getMatching(@PathVariable UUID id,
                                         @AuthenticationPrincipal JwtClaims claims) {
        var userId = claims != null ? claims.userId() : null;
        var data = loadMatchingForUser(id, userId);
        return ResponseEntity.ok(Map.of("data", data != null ? data : (Object) null));
    }

    @PostMapping("/matching")
    public ResponseEntity<?> enqueueMatching(@PathVariable UUID id,
                                             @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserOwner(claims, id);
        enqueueJob(id, "listing_matching_enrichment");
        return ResponseEntity.status(202).body(Map.of("data", Map.of("queued", true, "jobType", "listing_matching_enrichment")));
    }

    @GetMapping("/matching/status")
    public ResponseEntity<?> matchingStatus(@PathVariable UUID id) {
        var status = getJobStatus(id, "listing_matching_enrichment");
        return ResponseEntity.ok(Map.of("data", Map.of("status", status)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Object loadEnrichment(UUID listingId, String type) {
        return jdbc.sql("""
                SELECT result::text, status, completed_at
                FROM properia.listing_enrichments
                WHERE listing_id = :id AND enrichment_type = :type
                ORDER BY created_at DESC LIMIT 1
                """).param("id", listingId).param("type", type)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("listingId", listingId.toString());
                m.put("type", type);
                m.put("status", rs.getString("status"));
                m.put("completedAt", rs.getTimestamp("completed_at") != null
                    ? rs.getTimestamp("completed_at").toInstant().toString() : null);
                var result = rs.getString("result");
                m.put("result", result != null ? result : (Object) null);
                return (Object) m;
            }).optional().orElse(null);
    }

    private Object loadMatchingForUser(UUID listingId, UUID userId) {
        if (userId == null) return null;
        return jdbc.sql("""
                SELECT result::text, score, matched_at
                FROM properia.listing_matches
                WHERE listing_id = :lid AND user_id = :uid
                LIMIT 1
                """).param("lid", listingId).param("uid", userId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("listingId", listingId.toString());
                m.put("score", rs.getObject("score"));
                m.put("matchedAt", rs.getTimestamp("matched_at") != null
                    ? rs.getTimestamp("matched_at").toInstant().toString() : null);
                return (Object) m;
            }).optional().orElse(null);
    }

    private String getJobStatus(UUID listingId, String jobType) {
        return jdbc.sql("""
                SELECT status FROM properia.job_executions
                WHERE entity_id = :id AND job_type = :type
                ORDER BY created_at DESC LIMIT 1
                """).param("id", listingId.toString()).param("type", jobType)
            .query((rs, n) -> rs.getString("status"))
            .optional().orElse("not_started");
    }

    private void enqueueJob(UUID listingId, String jobType) {
        try {
            jdbc.sql("""
                    INSERT INTO properia.job_executions
                      (id, job_type, entity_type, entity_id, status, payload, created_at, updated_at)
                    VALUES (:id, :type, 'listing', :eid, 'queued', :payload::jsonb, now(), now())
                    """)
                .param("id", UUID.randomUUID())
                .param("type", jobType)
                .param("eid", listingId.toString())
                .param("payload", "{\"listingId\":\"" + listingId + "\"}")
                .update();
        } catch (Exception ignored) {}
    }

    private void requireAdvertiserOwner(JwtClaims claims, UUID listingId) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
        jdbc.sql("SELECT 1 FROM properia.listings l JOIN properia.advertiser_users au ON au.advertiser_id = l.advertiser_id WHERE l.id = :id AND au.user_id = :uid")
            .param("id", listingId).param("uid", claims.userId())
            .query((rs, n) -> 1).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem acesso.", 403));
    }
}
