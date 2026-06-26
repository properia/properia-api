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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class LeadController {

    private final CreateLeadUseCase createLead;
    private final UpdateLeadStageUseCase updateLeadStage;
    private final LeadStageAdvancer leadStageAdvancer;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public LeadController(
            CreateLeadUseCase createLead,
            UpdateLeadStageUseCase updateLeadStage,
            LeadStageAdvancer leadStageAdvancer,
            JdbcClient jdbc,
            ObjectMapper objectMapper) {
        this.createLead = createLead;
        this.updateLeadStage = updateLeadStage;
        this.leadStageAdvancer = leadStageAdvancer;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
    public ResponseEntity<?> listForAdvertiser(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String slaBucket,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String assignedToUserId) {
        var advertiserId = requireAdvertiserId(claims);

        var whereParts = new ArrayList<String>();
        var params = new java.util.LinkedHashMap<String, Object>();
        whereParts.add("l.advertiser_id = :adv");
        params.put("adv", advertiserId);

        if (stage != null && !stage.isBlank() && !"todos".equals(stage)) {
            whereParts.add("l.stage::text = :stage");
            params.put("stage", stage);
        }
        if (source != null && !source.isBlank() && !"todas".equals(source)) {
            whereParts.add("l.source::text = :source");
            params.put("source", source);
        }
        if (assignedToUserId != null && !assignedToUserId.isBlank()) {
            whereParts.add("l.assigned_to = :assignedTo::uuid");
            params.put("assignedTo", assignedToUserId);
        }
        if (q != null && !q.isBlank()) {
            whereParts.add("(l.contact_name ILIKE :q OR l.contact_email ILIKE :q OR l.message ILIKE :q)");
            params.put("q", "%" + q + "%");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            whereParts.add("l.created_at >= :dateFrom::timestamptz");
            params.put("dateFrom", dateFrom);
        }
        if (dateTo != null && !dateTo.isBlank()) {
            whereParts.add("l.created_at <= :dateTo::timestamptz");
            params.put("dateTo", dateTo);
        }

        var whereClause = "WHERE " + String.join(" AND ", whereParts);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safePageSize;

        var countSql = "SELECT COUNT(*) FROM properia.leads l " + whereClause;
        var countQuery = jdbc.sql(countSql);
        for (var e : params.entrySet()) countQuery = countQuery.param(e.getKey(), e.getValue());
        long total = countQuery.query(Long.class).single();

        var listSql = """
                SELECT l.id, l.listing_id, l.advertiser_id, l.source, l.stage,
                       l.intent_type, l.message, l.contact_name, l.contact_email,
                       l.contact_phone, l.assigned_to, l.metadata, l.created_at, l.updated_at,
                       li.id AS li_id, li.public_id AS li_public_id,
                       li.title AS li_title, li.business_type AS li_business_type,
                       li.status AS li_status, li.city AS li_city, li.district AS li_district
                FROM properia.leads l
                LEFT JOIN properia.listings li ON li.id = l.listing_id
                """ + whereClause + " ORDER BY l.created_at DESC LIMIT :lim OFFSET :off";
        var listQuery = jdbc.sql(listSql);
        for (var e : params.entrySet()) listQuery = listQuery.param(e.getKey(), e.getValue());
        listQuery = listQuery.param("lim", safePageSize).param("off", offset);

        var now = java.time.Instant.now();
        var items = listQuery.query((rs, n) -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", rs.getString("id"));
            m.put("listingId", rs.getString("listing_id"));
            m.put("advertiserId", rs.getString("advertiser_id"));
            m.put("source", rs.getString("source"));
            m.put("stage", rs.getString("stage"));
            m.put("intentType", rs.getString("intent_type") != null ? rs.getString("intent_type") : "");
            m.put("message", rs.getString("message"));
            m.put("contactName", rs.getString("contact_name"));
            m.put("contactEmail", rs.getString("contact_email"));
            m.put("contactPhone", rs.getString("contact_phone"));
            m.put("isLocked", false);
            m.put("responseCount", 0);
            m.put("lastResponseAt", null);
            m.put("hasDecisionDossier", false);
            m.put("proposal", null);
            m.put("timeline", List.of());
            m.put("conversation", List.of());

            // Compute slaBucket from age
            var createdAt = rs.getTimestamp("created_at").toInstant();
            m.put("createdAt", createdAt.toString());
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            long ageHours = Duration.between(createdAt, now).toHours();
            String bucket = ageHours < 24 ? "fresh" : ageHours < 72 ? "attention" : "late";
            m.put("slaBucket", bucket);
            m.put("priority", ageHours >= 72 ? "high" : ageHours >= 24 ? "medium" : "low");

            // Metadata — parse jsonb, default to safe shape
            var metaJson = rs.getString("metadata");
            var meta = new LinkedHashMap<String, Object>();
            meta.put("internalNotes", List.of());
            meta.put("events", List.of());
            meta.put("openedAt", null);
            meta.put("openedByUserId", null);
            meta.put("closeReason", null);
            if (metaJson != null && !metaJson.isBlank() && !metaJson.equals("{}")) {
                try {
                    @SuppressWarnings("unchecked")
                    var parsed = objectMapper.readValue(metaJson, Map.class);
                    if (parsed.containsKey("internalNotes")) meta.put("internalNotes", parsed.get("internalNotes"));
                    if (parsed.containsKey("events")) meta.put("events", parsed.get("events"));
                    if (parsed.containsKey("openedAt")) meta.put("openedAt", parsed.get("openedAt"));
                    if (parsed.containsKey("openedByUserId")) meta.put("openedByUserId", parsed.get("openedByUserId"));
                    if (parsed.containsKey("closeReason")) meta.put("closeReason", parsed.get("closeReason"));
                    if (parsed.containsKey("closeSummary")) meta.put("closeSummary", parsed.get("closeSummary"));
                    // Dados de qualificação/dossier escritos pelo chat — antes descartados
                    if (parsed.containsKey("chatQualification")) meta.put("chatQualification", parsed.get("chatQualification"));
                    if (parsed.containsKey("decisionDossier")) {
                        meta.put("decisionDossier", parsed.get("decisionDossier"));
                        if (parsed.get("decisionDossier") != null) m.put("hasDecisionDossier", true);
                    }
                    // Proposta lida do metadata quando exista persistência; null caso contrário
                    if (parsed.containsKey("proposal") && parsed.get("proposal") != null) {
                        m.put("proposal", parsed.get("proposal"));
                    }
                } catch (Exception ignored) {}
            }
            m.put("metadata", meta);

            // Listing sub-object
            var listing = new LinkedHashMap<String, Object>();
            listing.put("id", rs.getString("li_id") != null ? rs.getString("li_id") : rs.getString("listing_id"));
            listing.put("publicId", rs.getString("li_public_id") != null ? rs.getString("li_public_id") : "");
            listing.put("title", rs.getString("li_title") != null ? rs.getString("li_title") : "Imóvel");
            listing.put("businessType", rs.getString("li_business_type") != null ? rs.getString("li_business_type") : "");
            listing.put("status", rs.getString("li_status") != null ? rs.getString("li_status") : "");
            listing.put("city", rs.getString("li_city"));
            listing.put("district", rs.getString("li_district"));
            m.put("listing", listing);

            return (Map<String, Object>) m;
        }).list();

        // Apply slaBucket filter after computation (can't do it in SQL easily)
        if (slaBucket != null && !slaBucket.isBlank() && !"todas".equals(slaBucket)) {
            items = items.stream().filter(item -> slaBucket.equals(item.get("slaBucket"))).toList();
        }
        if (priority != null && !priority.isBlank() && !"todas".equals(priority)) {
            items = items.stream().filter(item -> priority.equals(item.get("priority"))).toList();
        }

        // ── Enriquecimento (em lote, sem N+1): conversa de chat, respostas comerciais e timeline ──
        // O contrato do FE espera conversation/responseCount/lastResponseAt/timeline reais.
        // Antes vinham sempre vazios ("o ecrã mentia"). Carregamos só para a página atual.
        if (!items.isEmpty()) {
            var leadIds = items.stream()
                .map(it -> UUID.fromString((String) it.get("id")))
                .toList();

            // Chat → conversation (asc), última msg outbound e nº de outbound por lead
            var conversationByLead = new HashMap<UUID, List<Map<String, Object>>>();
            var lastOutboundByLead = new HashMap<UUID, Instant>();
            var outboundCountByLead = new HashMap<UUID, Integer>();
            jdbc.sql("""
                    SELECT lead_id, id, sender_type::text AS sender_type, body, created_at
                    FROM properia.chat_messages
                    WHERE lead_id IN (:ids)
                    ORDER BY created_at ASC
                    """)
                .param("ids", leadIds)
                .query((rs, n) -> {
                    var leadId = UUID.fromString(rs.getString("lead_id"));
                    var senderType = rs.getString("sender_type");
                    var createdAt = rs.getTimestamp("created_at").toInstant();
                    var direction = "buyer".equals(senderType) ? "inbound"
                        : "advertiser_member".equals(senderType) ? "outbound" : "internal";
                    var title = "inbound".equals(direction) ? "Mensagem do comprador"
                        : "outbound".equals(direction) ? "Resposta enviada" : "Mensagem de sistema";
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("id", rs.getString("id"));
                    entry.put("direction", direction);
                    entry.put("channel", "message");
                    entry.put("title", title);
                    entry.put("body", rs.getString("body"));
                    entry.put("createdAt", createdAt.toString());
                    conversationByLead.computeIfAbsent(leadId, k -> new ArrayList<>()).add(entry);
                    if ("outbound".equals(direction)) {
                        outboundCountByLead.merge(leadId, 1, Integer::sum);
                        lastOutboundByLead.merge(leadId, createdAt, (a, b) -> b.isAfter(a) ? b : a);
                    }
                    return leadId;
                }).list();

            // Respostas comerciais → timeline + contagem + última resposta
            var responseTimelineByLead = new HashMap<UUID, List<Map<String, Object>>>();
            var responseCountByLead = new HashMap<UUID, Integer>();
            var lastResponseByLead = new HashMap<UUID, Instant>();
            jdbc.sql("""
                    SELECT lead_id, id, response_type, note, created_at
                    FROM properia.lead_responses
                    WHERE lead_id IN (:ids)
                    ORDER BY created_at ASC
                    """)
                .param("ids", leadIds)
                .query((rs, n) -> {
                    var leadId = UUID.fromString(rs.getString("lead_id"));
                    var createdAt = rs.getTimestamp("created_at").toInstant();
                    var note = rs.getString("note");
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("id", rs.getString("id"));
                    entry.put("type", "response");
                    entry.put("title", responseTypeLabel(rs.getString("response_type")));
                    entry.put("description", note != null ? note : "");
                    entry.put("createdAt", createdAt.toString());
                    responseTimelineByLead.computeIfAbsent(leadId, k -> new ArrayList<>()).add(entry);
                    responseCountByLead.merge(leadId, 1, Integer::sum);
                    lastResponseByLead.merge(leadId, createdAt, (a, b) -> b.isAfter(a) ? b : a);
                    return leadId;
                }).list();

            for (var it : items) {
                var leadId = UUID.fromString((String) it.get("id"));

                it.put("conversation", conversationByLead.getOrDefault(leadId, List.of()));

                // "Respondido" = respostas comerciais registadas + mensagens enviadas no chat
                it.put("responseCount",
                    responseCountByLead.getOrDefault(leadId, 0)
                        + outboundCountByLead.getOrDefault(leadId, 0));

                var lastAny = lastResponseByLead.get(leadId);
                var lastOut = lastOutboundByLead.get(leadId);
                if (lastOut != null && (lastAny == null || lastOut.isAfter(lastAny))) lastAny = lastOut;
                it.put("lastResponseAt", lastAny != null ? lastAny.toString() : null);

                // Timeline: evento de criação + respostas (as mensagens ficam em conversation)
                var timeline = new ArrayList<Map<String, Object>>();
                var createdEvent = new LinkedHashMap<String, Object>();
                createdEvent.put("id", "created-" + leadId);
                createdEvent.put("type", "created");
                createdEvent.put("title", "Lead criado");
                createdEvent.put("description", "Contacto entrou no CRM.");
                createdEvent.put("createdAt", it.get("createdAt"));
                timeline.add(createdEvent);
                timeline.addAll(responseTimelineByLead.getOrDefault(leadId, List.of()));
                it.put("timeline", timeline);
            }
        }

        int totalPages = (int) Math.ceil((double) total / safePageSize);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        result.put("totalPages", Math.max(1, totalPages));

        return ResponseEntity.ok(Map.of("data", result));
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

        updateLeadStage.execute(new UpdateLeadStageUseCase.Command(id, advertiserId, stage, assignedTo, null));
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    // ── Full lead update (notes, contact, proposal, etc.) ──────────────────────

    @PatchMapping("/api/advertiser/leads/{id}")
    public ResponseEntity<?> updateLead(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {

        var advertiserId = requireAdvertiserId(claims);
        boolean stageOrCloseReasonHandled = false;

        // Mudança de etapa e/ou motivo de desfecho passam pelo use case, que aplica
        // as guardas de transição (estados terminais) e a obrigatoriedade do motivo.
        if (body.containsKey("stage") || body.containsKey("closeReason")) {
            var stage = body.containsKey("stage") ? (String) body.get("stage") : null;
            var closeReason = body.containsKey("closeReason") ? (String) body.get("closeReason") : null;
            updateLeadStage.execute(new UpdateLeadStageUseCase.Command(id, advertiserId, stage, null, closeReason));
            stageOrCloseReasonHandled = true;
        }

        // Campos guardados no metadata jsonb (proposta, notas internas, abertura, resumo de
        // fecho). Antes eram aceites pelo contrato mas descartados — o FE mostrava sucesso
        // sem nada persistir. Corre depois do updateLeadStage para ler o metadata já com o
        // closeReason commitado e não o sobrescrever.
        if (body.containsKey("proposal") || body.containsKey("appendInternalNote")
                || body.containsKey("markOpened") || body.containsKey("closeSummary")) {
            mergeLeadMetadata(id, advertiserId, claims, body);
            stageOrCloseReasonHandled = true;

            // Guardar uma proposta avança o lead para 'proposal' (forward-only).
            if (body.get("proposal") != null) {
                leadStageAdvancer.advanceForward(id, advertiserId, "proposal");
            }
        }

        var sets = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);
        params.put("adv", advertiserId);

        if (body.containsKey("contactName")) { sets.add("contact_name = :contactName"); params.put("contactName", body.get("contactName")); }
        if (body.containsKey("contactEmail")) { sets.add("contact_email = :contactEmail"); params.put("contactEmail", body.get("contactEmail")); }
        if (body.containsKey("contactPhone")) { sets.add("contact_phone = :contactPhone"); params.put("contactPhone", body.get("contactPhone")); }
        if (body.containsKey("assignedToUserId")) {
            var v = body.get("assignedToUserId");
            sets.add("assigned_to = :assignedTo");
            params.put("assignedTo", v != null ? UUID.fromString(v.toString()) : null);
        }

        if (sets.isEmpty()) return ResponseEntity.ok(Map.of("data", Map.of("updated", stageOrCloseReasonHandled)));

        sets.add("updated_at = now()");
        var sql = "UPDATE properia.leads SET " + String.join(", ", sets) + " WHERE id = :id AND advertiser_id = :adv";
        var q = jdbc.sql(sql);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        var updated = q.update();
        if (updated == 0 && !stageOrCloseReasonHandled) throw new DomainException("NOT_FOUND", "Lead não encontrado.", 404);

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
                m.put("internalNotes", null);
                m.put("assignedToUserId", rs.getString("assigned_to"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));
        return ResponseEntity.ok(Map.of("data", lead));
    }

    /** Lê-modifica-escreve o metadata jsonb do lead com os campos não-relacionais. */
    @SuppressWarnings("unchecked")
    private void mergeLeadMetadata(UUID id, UUID advertiserId, JwtClaims claims, Map<String, Object> body) {
        var currentJson = jdbc.sql("SELECT metadata FROM properia.leads WHERE id = :id AND advertiser_id = :adv")
            .param("id", id).param("adv", advertiserId)
            .query(String.class).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));

        Map<String, Object> meta;
        try {
            meta = (currentJson != null && !currentJson.isBlank())
                ? new LinkedHashMap<>(objectMapper.readValue(currentJson, Map.class))
                : new LinkedHashMap<>();
        } catch (Exception e) {
            meta = new LinkedHashMap<>();
        }

        // markOpened — regista a primeira abertura (openedAt/openedByUserId), idempotente
        if (Boolean.TRUE.equals(body.get("markOpened")) && meta.get("openedAt") == null) {
            meta.put("openedAt", Instant.now().toString());
            meta.put("openedByUserId",
                claims != null && claims.userId() != null ? claims.userId().toString() : null);
        }

        // appendInternalNote — acrescenta nota ao histórico
        if (body.containsKey("appendInternalNote")) {
            var raw = body.get("appendInternalNote");
            if (raw != null && !raw.toString().isBlank()) {
                var notes = new ArrayList<Object>();
                if (meta.get("internalNotes") instanceof List<?> existing) notes.addAll(existing);
                var note = new LinkedHashMap<String, Object>();
                note.put("id", UUID.randomUUID().toString());
                note.put("text", raw.toString().trim());
                note.put("createdAt", Instant.now().toString());
                notes.add(note);
                meta.put("internalNotes", notes);
            }
        }

        // closeSummary — resumo livre do desfecho
        if (body.containsKey("closeSummary")) {
            var raw = body.get("closeSummary");
            meta.put("closeSummary", raw != null ? raw.toString() : null);
        }

        // proposal — merge parcial; null remove a proposta
        if (body.containsKey("proposal")) {
            var raw = body.get("proposal");
            if (raw == null) {
                meta.remove("proposal");
            } else if (raw instanceof Map<?, ?> incoming) {
                var proposal = new LinkedHashMap<String, Object>();
                if (meta.get("proposal") instanceof Map<?, ?> existing) {
                    for (var e : existing.entrySet()) proposal.put(e.getKey().toString(), e.getValue());
                }
                for (var e : incoming.entrySet()) proposal.put(e.getKey().toString(), e.getValue());
                proposal.put("currency", "EUR");
                proposal.put("updatedAt", Instant.now().toString());
                meta.put("proposal", proposal);
            }
        }

        try {
            var json = objectMapper.writeValueAsString(meta);
            jdbc.sql("UPDATE properia.leads SET metadata = :meta::jsonb, updated_at = now() WHERE id = :id AND advertiser_id = :adv")
                .param("meta", json).param("id", id).param("adv", advertiserId).update();
        } catch (Exception e) {
            throw new DomainException("INTERNAL", "Não foi possível guardar os dados do lead.", 500);
        }
    }

    private static String responseTypeLabel(String type) {
        if (type == null) return "Resposta registada";
        return switch (type) {
            case "call" -> "Chamada registada";
            case "email" -> "Email enviado";
            case "whatsapp" -> "Mensagem WhatsApp";
            case "sms" -> "SMS enviado";
            case "meeting" -> "Reunião realizada";
            default -> "Resposta registada";
        };
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
