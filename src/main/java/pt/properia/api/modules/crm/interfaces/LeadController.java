package pt.properia.api.modules.crm.interfaces;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.crm.application.lead.*;
import pt.properia.api.modules.crm.interfaces.request.CreateLeadRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class LeadController {

    private final CreateLeadUseCase createLead;
    private final GetAdvertiserLeadsUseCase getAdvertiserLeads;
    private final UpdateLeadStageUseCase updateLeadStage;
    private final JdbcClient jdbc;

    public LeadController(
            CreateLeadUseCase createLead,
            GetAdvertiserLeadsUseCase getAdvertiserLeads,
            UpdateLeadStageUseCase updateLeadStage,
            JdbcClient jdbc) {
        this.createLead = createLead;
        this.getAdvertiserLeads = getAdvertiserLeads;
        this.updateLeadStage = updateLeadStage;
        this.jdbc = jdbc;
    }

    // ── Public: buyer submits a lead ────────────────────────────────────────

    @PostMapping("/api/leads")
    public ResponseEntity<?> create(
            @AuthenticationPrincipal JwtClaims claims,
            @Valid @RequestBody CreateLeadRequest req) {

        var lead = createLead.execute(new CreateLeadUseCase.Command(
            req.listingId(),
            claims != null ? claims.userId() : null,
            req.source(),
            req.intentType(),
            req.message(),
            req.contactName(),
            req.contactEmail(),
            req.contactPhone(),
            "{}"
        ));

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", lead.getId(),
            "stage", lead.getStage()
        )));
    }

    // ── Advertiser CRM ──────────────────────────────────────────────────────

    @GetMapping("/api/advertiser/leads")
    public ResponseEntity<?> listForAdvertiser(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        return ResponseEntity.ok(Map.of("data", getAdvertiserLeads.execute(advertiserId)));
    }

    @PatchMapping("/api/advertiser/leads/{id}/stage")
    public ResponseEntity<?> updateStage(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {

        var advertiserId = requireAdvertiserId(claims);
        var stage = (String) body.get("stage");
        var assignedToRaw = body.get("assignedTo");
        UUID assignedTo = assignedToRaw != null ? UUID.fromString(assignedToRaw.toString()) : null;

        updateLeadStage.execute(new UpdateLeadStageUseCase.Command(id, advertiserId, stage, assignedTo));
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    // ── Full lead update (notes, contact, proposal, etc.) ──────────────────────

    @PatchMapping("/api/advertiser/leads/{id}")
    public ResponseEntity<?> updateLead(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {

        var advertiserId = requireAdvertiserId(claims);

        var sets = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);
        params.put("adv", advertiserId);

        if (body.containsKey("contactName")) { sets.add("contact_name = :contactName"); params.put("contactName", body.get("contactName")); }
        if (body.containsKey("contactEmail")) { sets.add("contact_email = :contactEmail"); params.put("contactEmail", body.get("contactEmail")); }
        if (body.containsKey("contactPhone")) { sets.add("contact_phone = :contactPhone"); params.put("contactPhone", body.get("contactPhone")); }
        if (body.containsKey("internalNotes")) { sets.add("internal_notes = :internalNotes"); params.put("internalNotes", body.get("internalNotes")); }
        if (body.containsKey("stage")) { sets.add("stage = :stage"); params.put("stage", body.get("stage")); }
        if (body.containsKey("assignedToUserId")) {
            var v = body.get("assignedToUserId");
            sets.add("assigned_to_user_id = :assignedTo");
            params.put("assignedTo", v != null ? UUID.fromString(v.toString()) : null);
        }
        if (body.containsKey("nextFollowUpAt")) {
            var v = body.get("nextFollowUpAt");
            sets.add("next_follow_up_at = :followUpAt");
            params.put("followUpAt", v != null ? Instant.parse(v.toString()) : null);
        }

        if (sets.isEmpty()) return ResponseEntity.ok(Map.of("data", Map.of("updated", false)));

        sets.add("updated_at = now()");
        var sql = "UPDATE properia.leads SET " + String.join(", ", sets) + " WHERE id = :id AND advertiser_id = :adv";
        var q = jdbc.sql(sql);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        var updated = q.update();
        if (updated == 0) throw new DomainException("NOT_FOUND", "Lead não encontrado.", 404);

        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    // ── Individual lead GET ─────────────────────────────────────────────────────

    @GetMapping("/api/advertiser/leads/{id}")
    public ResponseEntity<?> getLead(@PathVariable UUID id,
                                     @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var lead = jdbc.sql("""
                SELECT l.*, li.title as listing_title, li.hero_image_url as listing_hero_image
                FROM properia.leads l
                LEFT JOIN properia.listings li ON li.id = l.listing_id
                WHERE l.id = :id AND l.advertiser_id = :adv
                """).param("id", id).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("advertiserId", rs.getString("advertiser_id"));
                m.put("listingId", rs.getString("listing_id"));
                m.put("listingTitle", rs.getString("listing_title"));
                m.put("contactName", rs.getString("contact_name"));
                m.put("contactEmail", rs.getString("contact_email"));
                m.put("contactPhone", rs.getString("contact_phone"));
                m.put("stage", rs.getString("stage"));
                m.put("source", rs.getString("source"));
                m.put("internalNotes", rs.getString("internal_notes"));
                m.put("assignedToUserId", rs.getString("assigned_to_user_id"));
                m.put("nextFollowUpAt", rs.getTimestamp("next_follow_up_at") != null
                    ? rs.getTimestamp("next_follow_up_at").toInstant().toString() : null);
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));
        return ResponseEntity.ok(Map.of("data", lead));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
