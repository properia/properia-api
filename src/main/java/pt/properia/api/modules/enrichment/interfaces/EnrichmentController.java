package pt.properia.api.modules.enrichment.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.enrichment.vision.application.VisionService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

/**
 * Handles enrichment endpoints for listing enrichment (zone, POIs, vision, matching).
 * Vision analysis is synchronous (OpenAI GPT-4 vision). Others queue async jobs.
 */
@RestController
@RequestMapping("/api/enrichment/listings/{id}")
public class EnrichmentController {

    private final JdbcClient jdbc;
    private final VisionService visionService;

    public EnrichmentController(JdbcClient jdbc, VisionService visionService) {
        this.jdbc = jdbc;
        this.visionService = visionService;
    }

    // ── Zone enrichment ────────────────────────────────────────────────────────

    @GetMapping("/zone")
    public ResponseEntity<?> getZone(@PathVariable UUID id) {
        var data = loadEnrichment(id, "zone");
        var resp = new LinkedHashMap<String, Object>();
        resp.put("data", data);
        return ResponseEntity.ok(resp);
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
        var resp = new LinkedHashMap<String, Object>();
        resp.put("data", data);
        return ResponseEntity.ok(resp);
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
        var data = loadVisionSummary(id);
        var resp = new LinkedHashMap<String, Object>();
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/vision")
    public ResponseEntity<?> enqueueVision(@PathVariable UUID id,
                                           @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserOwner(claims, id);
        enqueueJob(id, "listing_vision_enrichment");
        var result = visionService.analyzeListingImages(id);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping("/vision/status")
    public ResponseEntity<?> visionStatus(@PathVariable UUID id) {
        var jobStatus = getJobStatus(id, "listing_vision_enrichment");
        var visionSummary = loadVisionSummary(id);
        var resp = new LinkedHashMap<String, Object>();
        resp.put("listingId", id.toString());
        resp.put("hasVisionSummary", visionSummary != null);
        resp.put("latestJob", Map.of("status", jobStatus));
        resp.put("visionSummary", visionSummary);
        return ResponseEntity.ok(Map.of("data", resp));
    }

    // ── Matching enrichment ────────────────────────────────────────────────────

    @GetMapping("/matching")
    public ResponseEntity<?> getMatching(@PathVariable UUID id,
                                         @AuthenticationPrincipal JwtClaims claims) {
        var userId = claims != null ? claims.userId() : null;
        var data = loadMatchingForUser(id, userId);
        var resp = new LinkedHashMap<String, Object>();
        resp.put("data", data);
        return ResponseEntity.ok(resp);
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

    private Object loadVisionSummary(UUID listingId) {
        return jdbc.sql("""
                SELECT version, provider, model, processed_at,
                       styles_detected::text, style_primary, style_secondary,
                       condition_ai::text, condition_confidence, quality_score,
                       furniture_detected::text, rooms_detected::text,
                       materials_detected::text, signals_detected::text,
                       light_quality_score, spaciousness_score, layout_quality_score,
                       premium_score, family_friendly_score, home_office_score, luxury_score,
                       needs_human_review, raw_response::text
                FROM properia.listing_ai_vision
                WHERE listing_id = :id
                """).param("id", listingId)
            .query((rs, n) -> {
                var raw = rs.getString("raw_response");
                var m = new LinkedHashMap<String, Object>();
                m.put("version", rs.getInt("version"));
                m.put("provider", rs.getString("provider"));
                m.put("model", rs.getString("model"));
                var pAt = rs.getTimestamp("processed_at");
                m.put("processedAt", pAt != null ? pAt.toInstant().toString() : null);
                m.put("conditionAi", raw != null ? extractRawString(raw, "conditionAi") : rs.getString("condition_ai"));
                m.put("conditionConfidence", rs.getObject("condition_confidence"));
                m.put("qualityScore", rs.getObject("quality_score"));
                m.put("lightQualityScore", rs.getObject("light_quality_score"));
                m.put("spaciousnessScore", rs.getObject("spaciousness_score"));
                m.put("layoutQualityScore", rs.getObject("layout_quality_score"));
                m.put("premiumScore", rs.getObject("premium_score"));
                m.put("familyFriendlyScore", rs.getObject("family_friendly_score"));
                m.put("homeOfficeScore", rs.getObject("home_office_score"));
                m.put("luxuryScore", rs.getObject("luxury_score"));
                m.put("needsHumanReview", rs.getBoolean("needs_human_review"));
                m.put("stylePrimary", rs.getString("style_primary"));
                m.put("styleSecondary", rs.getString("style_secondary"));
                m.put("stylesDetected", parseJsonArray(rs.getString("styles_detected")));
                m.put("furnitureDetected", parseJsonArray(rs.getString("furniture_detected")));
                m.put("roomsDetected", parseJsonArray(rs.getString("rooms_detected")));
                m.put("materialsDetected", parseJsonArray(rs.getString("materials_detected")));
                m.put("signalsDetected", parseJsonArray(rs.getString("signals_detected")));
                m.put("sellingPoints", raw != null ? extractRawArray(raw, "sellingPoints") : List.of());
                m.put("buyerProfiles", raw != null ? extractRawArray(raw, "buyerProfiles") : List.of());
                m.put("buyerProfilePrimary", raw != null ? extractRawString(raw, "buyerProfilePrimary") : null);
                m.put("coherenceFlags", List.of());
                m.put("photoRankings", List.of());
                return (Object) m;
            }).optional().orElse(null);
    }

    private Object loadEnrichment(UUID listingId, String type) {
        // Only zone/pois types — return empty for unknown types
        return null;
    }

    private List<String> parseJsonArray(String json) {
        if (json == null) return List.of();
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!node.isArray()) return List.of();
            var list = new ArrayList<String>();
            for (var item : node) { if (!item.isNull()) list.add(item.asText()); }
            return list;
        } catch (Exception e) { return List.of(); }
    }

    private List<String> extractRawArray(String raw, String field) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw).path(field);
            if (!node.isArray()) return List.of();
            var list = new ArrayList<String>();
            for (var item : node) { if (!item.isNull()) list.add(item.asText()); }
            return list;
        } catch (Exception e) { return List.of(); }
    }

    private String extractRawString(String raw, String field) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw).path(field);
            if (node.isNull() || node.isMissingNode()) return null;
            var s = node.asText();
            return s.isBlank() || "null".equals(s) ? null : s;
        } catch (Exception e) { return null; }
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
                """).param("id", listingId).param("type", jobType)
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
