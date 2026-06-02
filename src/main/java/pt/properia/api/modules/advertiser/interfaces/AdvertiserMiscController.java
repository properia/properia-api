package pt.properia.api.modules.advertiser.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pt.properia.api.modules.media.infrastructure.R2UploadService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Handles miscellaneous advertiser endpoints not covered by dedicated controllers:
 * commercial-settings, team activity/ownership, leads reveal/responses,
 * listing moderation/vocacao, media delete, profile logo, automation.
 */
@RestController
public class AdvertiserMiscController {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final R2UploadService r2;
    private final Path localStorageDir;

    public AdvertiserMiscController(JdbcClient jdbc, ObjectMapper objectMapper, R2UploadService r2) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.r2 = r2;
        this.localStorageDir = Paths.get(System.getProperty("java.io.tmpdir"), "properia-uploads");
    }

    // ── Commercial settings ────────────────────────────────────────────────────

    @GetMapping("/api/advertiser/commercial-settings")
    public ResponseEntity<?> getCommercialSettings(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var raw = jdbc.sql("""
                SELECT brand_name, settings::text FROM properia.advertisers WHERE id = :id
                """).param("id", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("brandName", rs.getString("brand_name"));
                m.put("_settings", parseJson(rs.getString("settings")));
                return m;
            })
            .optional().orElse(new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        var saved = (Map<String, Object>) raw.getOrDefault("_settings", Map.of());

        var emailTemplate = new LinkedHashMap<String, Object>();
        emailTemplate.put("subject", getOrDefault(saved, "templateEmailSubject",
            "Sobre o imóvel {{listingTitle}}"));
        emailTemplate.put("body", getOrDefault(saved, "templateEmailBody",
            "Olá {{firstName}},\n\nObrigado pelo seu contacto sobre {{listingTitle}}.\n\nSe continuar com interesse, posso enviar mais detalhes ou combinar visita.\n\nCumprimentos,\n{{brandName}}"));

        var phoneTemplate = new LinkedHashMap<String, Object>();
        phoneTemplate.put("script", getOrDefault(saved, "templatePhoneScript",
            "Olá {{firstName}}, fala {{brandName}} sobre o imóvel {{listingTitle}}. Gostava de perceber se continua com interesse e se faz sentido avançarmos para visita."));

        var templates = new LinkedHashMap<String, Object>();
        templates.put("email", emailTemplate);
        templates.put("phone", phoneTemplate);

        var automation = new LinkedHashMap<String, Object>();
        automation.put("leadFollowUpHours", getOrDefaultInt(saved, "leadFollowUpHours", 6));
        automation.put("proposalFollowUpHours", getOrDefaultInt(saved, "proposalFollowUpHours", 48));

        var data = new LinkedHashMap<String, Object>();
        data.put("brandName", raw.get("brandName"));
        data.put("templates", templates);
        data.put("automation", automation);
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PatchMapping("/api/advertiser/commercial-settings")
    public ResponseEntity<?> updateCommercialSettings(@RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var existing = jdbc.sql("SELECT settings::text FROM properia.advertisers WHERE id = :id")
            .param("id", advertiserId)
            .query((rs, n) -> parseJson(rs.getString("settings")))
            .optional().orElse(new LinkedHashMap<>());
        var merged = new LinkedHashMap<>(existing);

        // Flatten nested templates/automation into the settings blob
        if (body.containsKey("templates")) {
            @SuppressWarnings("unchecked")
            var t = (Map<String, Object>) body.get("templates");
            if (t != null) {
                if (t.containsKey("email")) {
                    @SuppressWarnings("unchecked")
                    var e = (Map<String, Object>) t.get("email");
                    if (e != null) {
                        if (e.containsKey("subject")) merged.put("templateEmailSubject", e.get("subject"));
                        if (e.containsKey("body")) merged.put("templateEmailBody", e.get("body"));
                    }
                }
                if (t.containsKey("phone")) {
                    @SuppressWarnings("unchecked")
                    var p = (Map<String, Object>) t.get("phone");
                    if (p != null && p.containsKey("script")) merged.put("templatePhoneScript", p.get("script"));
                }
            }
        }
        if (body.containsKey("automation")) {
            @SuppressWarnings("unchecked")
            var a = (Map<String, Object>) body.get("automation");
            if (a != null) {
                if (a.containsKey("leadFollowUpHours")) merged.put("leadFollowUpHours", a.get("leadFollowUpHours"));
                if (a.containsKey("proposalFollowUpHours")) merged.put("proposalFollowUpHours", a.get("proposalFollowUpHours"));
            }
        }

        jdbc.sql("UPDATE properia.advertisers SET settings = :s::jsonb, updated_at = now() WHERE id = :id")
            .param("s", toJson(merged)).param("id", advertiserId).update();

        // Return in the same shape as GET
        var emailTemplate = new LinkedHashMap<String, Object>();
        emailTemplate.put("subject", merged.getOrDefault("templateEmailSubject", "Sobre o imóvel {{listingTitle}}"));
        emailTemplate.put("body", merged.getOrDefault("templateEmailBody", ""));
        var phoneTemplate = new LinkedHashMap<String, Object>();
        phoneTemplate.put("script", merged.getOrDefault("templatePhoneScript", ""));
        var templates = new LinkedHashMap<String, Object>();
        templates.put("email", emailTemplate);
        templates.put("phone", phoneTemplate);
        var automation = new LinkedHashMap<String, Object>();
        automation.put("leadFollowUpHours", getOrDefaultInt(merged, "leadFollowUpHours", 6));
        automation.put("proposalFollowUpHours", getOrDefaultInt(merged, "proposalFollowUpHours", 48));
        var data = new LinkedHashMap<String, Object>();
        data.put("brandName", null);
        data.put("templates", templates);
        data.put("automation", automation);
        return ResponseEntity.ok(Map.of("data", data));
    }

    private String getOrDefault(Map<String, Object> map, String key, String def) {
        var v = map.get(key);
        return v instanceof String s ? s : def;
    }

    private int getOrDefaultInt(Map<String, Object> map, String key, int def) {
        var v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    // ── Team activity ──────────────────────────────────────────────────────────

    @GetMapping("/api/advertiser/team/activity")
    public ResponseEntity<?> getTeamActivity(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        requireOwnerOrAdmin(advertiserId, claims.userId());

        var cap = Math.min(limit, 50);
        var items = jdbc.sql("""
                SELECT e.id, e.actor_user_id, u.full_name as actor_name,
                       e.action, e.entity_type, e.payload::text, e.created_at
                FROM properia.crm_audit_events e
                LEFT JOIN properia.app_users u ON u.id = e.actor_user_id
                WHERE e.advertiser_id = :adv
                  AND (:userId IS NULL OR e.actor_user_id = :userId::uuid)
                ORDER BY e.created_at DESC
                LIMIT :lim
                """)
            .param("adv", advertiserId)
            .param("userId", userId)
            .param("lim", cap)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("actorUserId", rs.getString("actor_user_id"));
                m.put("actorName", rs.getString("actor_name"));
                m.put("action", rs.getString("action"));
                m.put("entityType", rs.getString("entity_type"));
                m.put("payload", parseJson(rs.getString("payload")));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).list();

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    // ── Team ownership transfer ────────────────────────────────────────────────

    @PostMapping("/api/advertiser/team/ownership")
    public ResponseEntity<?> transferOwnership(@RequestBody Map<String, String> body,
                                               @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        requireRole(advertiserId, claims.userId(), "owner");

        var targetUserId = body.get("targetUserId");
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new DomainException("BAD_REQUEST", "targetUserId inválido.", 400);
        }
        if (targetUserId.equals(claims.userId().toString())) {
            throw new DomainException("BAD_REQUEST", "Já és o proprietário desta organização.", 400);
        }

        var targetUid = UUID.fromString(targetUserId);
        var target = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", targetUid)
            .query((rs, n) -> rs.getString("membership_role"))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Utilizador não encontrado nesta organização.", 404));

        if ("owner".equals(target)) {
            throw new DomainException("CONFLICT", "Este utilizador já é proprietário.", 409);
        }

        jdbc.sql("""
                UPDATE properia.advertiser_users SET membership_role = 'owner'
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", targetUid).update();
        jdbc.sql("""
                UPDATE properia.advertiser_users SET membership_role = 'admin'
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", claims.userId()).update();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "newOwnerUserId", targetUserId,
            "previousOwnerUserId", claims.userId().toString()
        )));
    }

    // ── Lead reveal ────────────────────────────────────────────────────────────

    @PostMapping("/api/advertiser/leads/{id}/reveal")
    public ResponseEntity<?> revealLead(@PathVariable UUID id,
                                        @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var lead = jdbc.sql("""
                SELECT id, advertiser_id, contact_name, contact_email, contact_phone,
                       stage, source, listing_id, contact_revealed_at
                FROM properia.leads
                WHERE id = :id AND advertiser_id = :adv
                """).param("id", id).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("advertiserId", rs.getString("advertiser_id"));
                m.put("contactName", rs.getString("contact_name"));
                m.put("contactEmail", rs.getString("contact_email"));
                m.put("contactPhone", rs.getString("contact_phone"));
                m.put("stage", rs.getString("stage"));
                m.put("source", rs.getString("source"));
                m.put("listingId", rs.getString("listing_id"));
                m.put("alreadyRevealed", rs.getTimestamp("contact_revealed_at") != null);
                return (Map<String, Object>) m;
            }).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));

        if (!Boolean.TRUE.equals(lead.get("alreadyRevealed"))) {
            jdbc.sql("""
                    UPDATE properia.leads SET contact_revealed_at = now(), updated_at = now()
                    WHERE id = :id
                    """).param("id", id).update();
        }

        return ResponseEntity.ok(Map.of("data", lead));
    }

    // ── Lead responses ─────────────────────────────────────────────────────────

    @PostMapping("/api/advertiser/leads/{id}/responses")
    public ResponseEntity<?> logLeadResponse(@PathVariable UUID id,
                                             @RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        // Verify lead belongs to advertiser
        jdbc.sql("SELECT 1 FROM properia.leads WHERE id = :id AND advertiser_id = :adv")
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> 1).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));

        var responseType = body.getOrDefault("responseType", "call").toString();
        var note = body.containsKey("note") ? body.get("note").toString() : null;
        var outcome = body.containsKey("outcome") ? body.get("outcome").toString() : null;

        var responseId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.lead_responses (id, lead_id, advertiser_id, actor_user_id,
                  response_type, note, outcome, created_at, updated_at)
                VALUES (:rid, :lid, :adv, :uid, :type, :note, :outcome, now(), now())
                """)
            .param("rid", responseId).param("lid", id).param("adv", advertiserId)
            .param("uid", claims.userId()).param("type", responseType)
            .param("note", note).param("outcome", outcome).update();

        // Log CRM audit event
        try {
            jdbc.sql("""
                    INSERT INTO properia.crm_audit_events (id, advertiser_id, actor_user_id, action,
                      entity_type, entity_id, payload, created_at)
                    VALUES (:id, :adv, :uid, 'commercial_response_logged', 'lead', :eid, :payload::jsonb, now())
                    """)
                .param("id", UUID.randomUUID()).param("adv", advertiserId).param("uid", claims.userId())
                .param("eid", id.toString())
                .param("payload", toJson(Map.of("responseType", responseType, "outcome", outcome != null ? outcome : "")))
                .update();
        } catch (Exception ignored) {}

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", responseId.toString(),
            "leadId", id.toString(),
            "responseType", responseType
        )));
    }

    // ── Listing moderation history ─────────────────────────────────────────────

    @GetMapping("/api/advertiser/listings/{id}/moderation")
    public ResponseEntity<?> getListingModeration(@PathVariable UUID id,
                                                  @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserId(claims);
        var records = jdbc.sql("""
                SELECT id, target_type, target_id, decision, reason_category,
                       public_reason, decided_at, decision_source
                FROM properia.moderation_decisions
                WHERE target_id = :id AND target_type = 'listing'
                ORDER BY decided_at DESC
                """).param("id", id)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("targetType", rs.getString("target_type"));
                m.put("targetId", rs.getString("target_id"));
                m.put("decision", rs.getString("decision"));
                m.put("reasonCategory", rs.getString("reason_category"));
                m.put("publicReason", rs.getString("public_reason"));
                m.put("decidedAt", rs.getTimestamp("decided_at") != null
                    ? rs.getTimestamp("decided_at").toInstant().toString() : null);
                m.put("decisionSource", rs.getString("decision_source"));
                return (Map<String, Object>) m;
            }).list();

        var latest = records.isEmpty() ? null : records.get(0);
        return ResponseEntity.ok(Map.of("data", Map.of(
            "latest", latest != null ? latest : (Object) null,
            "history", records
        )));
    }

    // ── Listing vocação (space vocation) ──────────────────────────────────────

    @GetMapping("/api/advertiser/listings/{id}/vocacao")
    public ResponseEntity<?> getVocacao(@PathVariable UUID id,
                                        @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserId(claims);
        var vocation = loadVocacao(id);
        var vocData = new java.util.LinkedHashMap<String, Object>();
        vocData.put("data", vocation);
        return ResponseEntity.ok(vocData);
    }

    @PostMapping("/api/advertiser/listings/{id}/vocacao")
    public ResponseEntity<?> analyzeVocacao(@PathVariable UUID id,
                                            @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserId(claims);
        // Return existing or create placeholder — AI analysis handled async
        var vocation = loadVocacao(id);
        if (vocation == null) {
            var placeholder = new java.util.LinkedHashMap<String, Object>();
            placeholder.put("listingId", id.toString());
            placeholder.put("status", "pending");
            placeholder.put("primaryUse", null);
            placeholder.put("message", "Análise ainda não disponível para este imóvel.");
            vocation = placeholder;
        }
        return ResponseEntity.ok(Map.of("data", vocation));
    }

    @PatchMapping("/api/advertiser/listings/{id}/vocacao")
    public ResponseEntity<?> confirmVocacao(@PathVariable UUID id,
                                            @RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserId(claims);
        var primaryUse = body.get("primaryUse");
        if (primaryUse != null) {
            jdbc.sql("""
                    UPDATE properia.listing_space_vocations
                    SET primary_use = :use, confirmed_at = now(), confirmed_by = :uid,
                        updated_at = now()
                    WHERE listing_id = :id
                    """).param("use", primaryUse.toString()).param("uid", claims.userId())
                .param("id", id).update();
        }
        var vocation = loadVocacao(id);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("data", vocation);
        return ResponseEntity.ok(result);
    }

    // ── Media delete ───────────────────────────────────────────────────────────

    @DeleteMapping("/api/advertiser/listings/{id}/media/{mediaId}")
    public ResponseEntity<?> deleteMedia(@PathVariable UUID id,
                                         @PathVariable UUID mediaId,
                                         @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        // Verify listing belongs to advertiser
        jdbc.sql("SELECT 1 FROM properia.listings WHERE id = :id AND advertiser_id = :adv")
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> 1).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Imóvel não encontrado.", 404));

        var deleted = jdbc.sql("""
                DELETE FROM properia.listing_media WHERE id = :mid AND listing_id = :lid
                RETURNING id, url
                """).param("mid", mediaId).param("lid", id)
            .query((rs, n) -> Map.of("id", rs.getString("id"), "url", Optional.ofNullable(rs.getString("url")).orElse("")))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Media não encontrada para este anúncio.", 404));

        return ResponseEntity.ok(Map.of("data", deleted));
    }

    // ── Profile logo ───────────────────────────────────────────────────────────

    @PostMapping(value = "/api/advertiser/profile/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadLogo(@RequestParam("file") MultipartFile file,
                                        @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        if (file.isEmpty()) throw new DomainException("BAD_REQUEST", "Ficheiro vazio.", 400);
        var contentType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
        if (!contentType.startsWith("image/")) {
            throw new DomainException("BAD_REQUEST", "Apenas imagens são aceites (PNG, JPEG, WEBP, SVG).", 400);
        }

        String logoUrl;
        try {
            var ext = contentType.contains("png") ? ".png"
                    : contentType.contains("svg") ? ".svg"
                    : contentType.contains("webp") ? ".webp"
                    : ".jpg";
            var objectKey = "advertisers/" + advertiserId + "/logo-" + UUID.randomUUID() + ext;
            if (r2.isConfigured()) {
                logoUrl = r2.uploadBytes(objectKey, file.getBytes(), contentType);
            } else {
                var target = localStorageDir.resolve(
                    Paths.get(objectKey).normalize()).normalize();
                Files.createDirectories(target.getParent());
                file.transferTo(target);
                logoUrl = "/api/local-storage/media/" + objectKey;
            }
        } catch (Exception e) {
            throw new DomainException("UPLOAD_ERROR", "Erro ao guardar o logo: " + e.getMessage(), 500);
        }

        jdbc.sql("UPDATE properia.advertisers SET logo_url = :url, updated_at = now() WHERE id = :id")
            .param("url", logoUrl).param("id", advertiserId).update();

        var result = new LinkedHashMap<String, Object>();
        result.put("logoUrl", logoUrl);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @DeleteMapping("/api/advertiser/profile/logo")
    public ResponseEntity<?> deleteLogo(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        jdbc.sql("UPDATE properia.advertisers SET logo_url = NULL, updated_at = now() WHERE id = :id")
            .param("id", advertiserId).update();
        var logoData = new LinkedHashMap<String, Object>();
        logoData.put("logoUrl", null);
        return ResponseEntity.ok(Map.of("data", logoData));
    }

    // ── Automation queue ───────────────────────────────────────────────────────

    @GetMapping("/api/advertiser/automation")
    public ResponseEntity<?> getAutomation(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        // Return overdue leads as automation tasks (same logic as pulse)
        var tasks = jdbc.sql("""
                SELECT l.id, l.contact_name, l.stage, l.listing_id, l.created_at,
                       li.title as listing_title
                FROM properia.leads l
                LEFT JOIN properia.listings li ON li.id = l.listing_id
                WHERE l.advertiser_id = :adv
                  AND l.stage NOT IN ('won', 'lost')
                ORDER BY l.created_at ASC
                LIMIT 20
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                var leadId = rs.getString("id");
                var listingId = rs.getString("listing_id");
                m.put("id", leadId);
                m.put("leadId", leadId);
                m.put("listingId", listingId);
                m.put("title", "Seguimento: " + Optional.ofNullable(rs.getString("contact_name")).orElse("Lead"));
                m.put("description", Optional.ofNullable(rs.getString("listing_title")).orElse(""));
                m.put("actionLabel", "Ver lead");
                m.put("priority", "new".equals(rs.getString("stage")) ? "high" : "medium");
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("href", "/anunciante/leads");
                return (Map<String, Object>) m;
            }).list();

        return ResponseEntity.ok(Map.of("data", Map.of("items", tasks)));
    }

    // ── Buyer resend consent ───────────────────────────────────────────────────

    @PostMapping("/api/advertiser/buyers/{id}/resend-consent")
    public ResponseEntity<?> resendBuyerConsent(@PathVariable UUID id,
                                                @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var buyer = jdbc.sql("""
                SELECT id, email, name, consent_token FROM properia.buyer_profiles
                WHERE id = :id AND advertiser_id = :adv
                """).param("id", id).param("adv", advertiserId)
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "email", Optional.ofNullable(rs.getString("email")).orElse(""),
                "name", Optional.ofNullable(rs.getString("name")).orElse("")
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        // Generate new consent token and reset status to pending
        var newToken = UUID.randomUUID();
        jdbc.sql("""
                UPDATE properia.buyer_profiles
                SET consent_token = :token, consent_status = 'pending'::properia.buyer_consent_status,
                    consent_accepted_at = NULL, updated_at = now()
                WHERE id = :id
                """).param("token", newToken).param("id", id).update();

        // Email sending is handled by async job / notification system
        return ResponseEntity.ok(Map.of("data", Map.of("sent", true, "email", buyer.get("email"))));
    }

    // ── Notifications read ─────────────────────────────────────────────────────

    @PostMapping("/api/advertiser/notifications/{id}/read")
    public ResponseEntity<?> markNotificationRead(@PathVariable String id,
                                                  @AuthenticationPrincipal JwtClaims claims) {
        requireAdvertiserId(claims);
        try {
            jdbc.sql("""
                    UPDATE properia.advertiser_notifications
                    SET read_at = now(), updated_at = now()
                    WHERE id = :id AND user_id = :uid AND read_at IS NULL
                    """).param("id", UUID.fromString(id)).param("uid", claims.userId()).update();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    @PostMapping("/api/advertiser/notifications/read-all")
    public ResponseEntity<?> markAllNotificationsRead(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        try {
            jdbc.sql("""
                    UPDATE properia.advertiser_notifications
                    SET read_at = now(), updated_at = now()
                    WHERE advertiser_id = :adv AND read_at IS NULL
                    """).param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Object loadVocacao(UUID listingId) {
        return jdbc.sql("""
                SELECT listing_id, primary_use, secondary_uses::text, adaptation_level,
                       open_to_remodeling, confidence_score, confirmed_at, updated_at
                FROM properia.listing_space_vocations WHERE listing_id = :id
                """).param("id", listingId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("listingId", rs.getString("listing_id"));
                m.put("primaryUse", rs.getString("primary_use"));
                m.put("secondaryUses", parseJsonArray(rs.getString("secondary_uses")));
                m.put("adaptationLevel", rs.getString("adaptation_level"));
                m.put("openToRemodeling", rs.getObject("open_to_remodeling"));
                m.put("confidenceScore", rs.getObject("confidence_score"));
                m.put("confirmedAt", rs.getTimestamp("confirmed_at") != null
                    ? rs.getTimestamp("confirmed_at").toInstant().toString() : null);
                m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                return (Object) m;
            }).optional().orElse(null);
    }

    private void requireRole(UUID advertiserId, UUID userId, String role) {
        var memberRole = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query((rs, n) -> rs.getString("membership_role")).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem acesso.", 403));
        if (!role.equals(memberRole)) {
            throw new DomainException("FORBIDDEN", "Permissão insuficiente.", 403);
        }
    }

    private void requireOwnerOrAdmin(UUID advertiserId, UUID userId) {
        var role = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query((rs, n) -> rs.getString("membership_role")).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem acesso.", 403));
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new DomainException("FORBIDDEN", "Sem acesso.", 403);
        }
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
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
