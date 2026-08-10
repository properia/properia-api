package pt.properia.api.modules.crm.interfaces;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.billing.application.BillingService;
import pt.properia.api.modules.crm.application.lead.*;
import pt.properia.api.modules.crm.interfaces.request.CreateLeadRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final BillingService billingService;

    // ── Lead score (0–88) — VALOR do lead, para a lente "Prioritários" ──────────
    // Requer os joins `li` (listings) e `lp` (listing_pricing) na query. Buckets:
    // high >=45, medium 25–44, low <25. Ver list() para a derivação de `priority`.
    private static final String LEAD_SCORE = """
        CASE l.stage::text
          WHEN 'proposal' THEN 30 WHEN 'visit_scheduled' THEN 24 WHEN 'qualified' THEN 22
          WHEN 'contacted' THEN 10 WHEN 'new' THEN 4 ELSE 0 END
        + CASE
            WHEN l.metadata->>'decisionDossier' IS NOT NULL OR l.metadata->>'chatQualification' IS NOT NULL THEN 25
            WHEN l.source::text IN ('visit_request','contact_request') THEN 14
            WHEN l.source::text = 'chat' THEN 8 ELSE 2 END
        + CASE
            WHEN lp.list_price IS NULL THEN 8
            WHEN li.business_type::text = 'rent' THEN
              CASE WHEN lp.list_price >= 2000 THEN 25 WHEN lp.list_price >= 1200 THEN 18
                   WHEN lp.list_price >= 700 THEN 12 ELSE 6 END
            ELSE
              CASE WHEN lp.list_price >= 600000 THEN 25 WHEN lp.list_price >= 300000 THEN 18
                   WHEN lp.list_price >= 150000 THEN 12 ELSE 6 END END
        + CASE WHEN l.created_at > now() - interval '48 hours' THEN 8 ELSE 0 END
        """;

    public LeadController(
            CreateLeadUseCase createLead,
            UpdateLeadStageUseCase updateLeadStage,
            LeadStageAdvancer leadStageAdvancer,
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            BillingService billingService) {
        this.createLead = createLead;
        this.updateLeadStage = updateLeadStage;
        this.leadStageAdvancer = leadStageAdvancer;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.billingService = billingService;
    }

    // ── Public: buyer submits a lead ────────────────────────────────────────

    @PostMapping("/api/leads")
    public ResponseEntity<?> create(
            @AuthenticationPrincipal JwtClaims claims,
            @Valid @RequestBody CreateLeadRequest req) {

        // req.metadata() carrega contexto de captação que não cabe no enum lead_source
        // (ex.: { sourceContext: 'estimated_costs_sidebar' }). Falha silenciosa em "{}"
        // se o mapa vier malformado — nunca deve bloquear a criação do lead.
        String metadataJson = "{}";
        if (req.metadata() != null && !req.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(req.metadata());
            } catch (Exception ignored) {}
        }

        var lead = createLead.execute(new CreateLeadUseCase.Command(
            req.listingId(),
            claims != null ? claims.userId() : null,
            req.source(),
            req.intentType(),
            req.message(),
            req.contactName(),
            req.contactEmail(),
            req.contactPhone(),
            metadataJson
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
        var slaHours = fetchSlaHours(advertiserId);

        var whereParts = new ArrayList<String>();
        var params = new java.util.LinkedHashMap<String, Object>();
        whereParts.add("l.advertiser_id = :adv");
        params.put("adv", advertiserId);
        params.put("leadHours", slaHours.leadHours());
        params.put("proposalHours", slaHours.proposalHours());

        if (stage != null && !stage.isBlank() && !"todos".equals(stage)) {
            whereParts.add("l.stage::text = :stage");
            params.put("stage", stage);
        }
        if (source != null && !source.isBlank() && !"todas".equals(source)) {
            whereParts.add("l.source::text = :source");
            params.put("source", source);
        }
        // Sales só vê os próprios leads — força o filtro, ignorando qualquer
        // assignedToUserId enviado pelo cliente (não confiar no filtro opcional da UI
        // para isolar dados de outros consultores).
        //
        // "Próprios" inclui os leads dos imóveis de que é responsável (li.owner_user_id),
        // não só os que lhe estão atribuídos diretamente (l.assigned_to): atribuir um
        // imóvel a alguém NÃO atribui os leads gerados nesse imóvel — são dois campos
        // independentes. Sem o fallback, um consultor com imóvel atribuído via a visita
        // na agenda (VisitController já tinha este OR) mas o lead correspondente
        // desaparecia da lista, e o Dashboard contava-o (AdvertiserMetricsService.
        // LEAD_SCOPE_SQL também já tinha) — KPI a dizer "1 lead" sobre uma lista vazia.
        if (isScopedToSelf(advertiserId, claims.userId())) {
            whereParts.add("(l.assigned_to = :assignedTo::uuid OR li.owner_user_id = :assignedTo::uuid)");
            params.put("assignedTo", claims.userId().toString());
        } else if (assignedToUserId != null && !assignedToUserId.isBlank()) {
            whereParts.add("l.assigned_to = :assignedTo::uuid");
            params.put("assignedTo", assignedToUserId);
        }
        if (q != null && !q.isBlank()) {
            whereParts.add("(l.contact_name ILIKE :q OR l.contact_email ILIKE :q OR l.contact_phone ILIKE :q OR l.message ILIKE :q)");
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
        // ── slaBucket (TEMPO) vs priority (VALOR) — duas lentes distintas ──────────
        // slaBucket mede o tempo DESDE A ÚLTIMA RESPOSTA da equipa (fresh/attention/
        // late) → higiene de resposta ("estou a falhar no tempo"). Antes media a idade
        // do lead (l.created_at), pelo que responder no chat nunca limpava "Fora do
        // prazo": um lead de 3 dias ficava atrasado para sempre, mesmo com respostas
        // enviadas. Agora o relógio reinicia a cada resposta real — leads.last_responded_at
        // é mantido por trigger a partir do chat (mensagens não-internas) e de
        // lead_responses (chamada/email registados). Sem resposta ainda → conta desde a
        // criação, que é o comportamento correto para um lead novo por atender.
        // Tem de ir para o WHERE em SQL — filtrar em memória DEPOIS do LIMIT/OFFSET
        // desalinha total/totalPages.
        // Leads fechados (won/lost) não têm SLA pendente, por isso são excluídos.
        // O limiar de "late" é configurável por agência (properia.advertisers.settings):
        // leadFollowUpHours para a generalidade dos leads, proposalFollowUpHours para os
        // que já estão em proposta (avisos diferentes: "sem resposta" vs "sem avanço na
        // proposta"). "fresh" é 1/3 desse limiar — preserva a proporção original (24/72).
        final String activeOnly = " AND l.stage::text NOT IN ('won','lost')";
        final String slaClock = "COALESCE(l.last_responded_at, l.created_at)";
        final String lateThresholdExpr =
            "make_interval(hours => CASE WHEN l.stage::text = 'proposal' THEN :proposalHours ELSE :leadHours END)";
        final String freshThresholdExpr =
            "make_interval(hours => (CASE WHEN l.stage::text = 'proposal' THEN :proposalHours ELSE :leadHours END) / 3)";
        if (slaBucket != null && !slaBucket.isBlank() && !"todas".equals(slaBucket)) {
            whereParts.add(switch (slaBucket) {
                case "fresh" -> slaClock + " > now() - " + freshThresholdExpr + activeOnly;
                case "attention" -> slaClock + " <= now() - " + freshThresholdExpr
                    + " AND " + slaClock + " > now() - " + lateThresholdExpr + activeOnly;
                case "late" -> slaClock + " <= now() - " + lateThresholdExpr + activeOnly;
                default -> "true";
            });
        }
        // priority deriva de um LEAD SCORE de VALOR (não da idade — antes era um
        // duplicado exato de slaBucket). Combina proximidade ao fecho (etapa),
        // intenção/qualificação (dossier·chat·origem), valor do imóvel (preço, venda
        // vs arrendamento) e um leve bónus de frescura. Responde a "onde investir
        // energia primeiro", complementar a "onde estou atrasado" (slaBucket).
        if (priority != null && !priority.isBlank() && !"todas".equals(priority)) {
            whereParts.add(switch (priority) {
                case "high" -> "(" + LEAD_SCORE + ") >= 45" + activeOnly;
                case "medium" -> "(" + LEAD_SCORE + ") >= 25 AND (" + LEAD_SCORE + ") < 45" + activeOnly;
                case "low" -> "(" + LEAD_SCORE + ") < 25";
                default -> "true";
            });
        }

        var whereClause = "WHERE " + String.join(" AND ", whereParts);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safePageSize;

        // li + lp juntos (mesmo em count) porque o filtro de prioridade referencia
        // o LEAD_SCORE, que usa li.business_type e lp.list_price. LEFT JOINs indexados
        // por listing_id — custo negligenciável.
        var fromClause = """
                FROM properia.leads l
                LEFT JOIN properia.listings li ON li.id = l.listing_id
                LEFT JOIN properia.listing_pricing lp ON lp.listing_id = l.listing_id
                """;
        var countSql = "SELECT COUNT(*) " + fromClause + whereClause;
        var countQuery = jdbc.sql(countSql);
        for (var e : params.entrySet()) countQuery = countQuery.param(e.getKey(), e.getValue());
        long total = countQuery.query(Long.class).single();

        var listSql = "SELECT l.id, l.listing_id, l.advertiser_id, l.source, l.stage,"
                + " l.intent_type, l.message, l.contact_name, l.contact_email,"
                + " l.contact_phone, l.contact_revealed_at, l.assigned_to, l.metadata,"
                + " l.created_at, l.updated_at, l.last_responded_at,"
                + " li.id AS li_id, li.public_id AS li_public_id,"
                + " li.title AS li_title, li.business_type AS li_business_type,"
                + " li.status AS li_status, li.city AS li_city, li.district AS li_district,"
                + " (" + LEAD_SCORE + ") AS lead_score "
                + fromClause + whereClause + " ORDER BY l.created_at DESC LIMIT :lim OFFSET :off";
        var listQuery = jdbc.sql(listSql);
        for (var e : params.entrySet()) listQuery = listQuery.param(e.getKey(), e.getValue());
        listQuery = listQuery.param("lim", safePageSize).param("off", offset);

        // Planos Pro+ têm os contactos sempre desbloqueados; Starter precisa de créditos
        // (ver /leads/{id}/reveal). Calculado uma vez para a página inteira — não por lead.
        boolean leadsUnlockedByPlan = billingService.hasLeadsUnlockedByPlan(advertiserId);

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

            // Contacto só sai em claro se o plano já o desbloqueia por omissão, ou se este
            // lead específico já foi pago/desbloqueado antes (contact_revealed_at). Caso
            // contrário, mascarado no servidor — a versão anterior enviava sempre os dados
            // reais e confiava só na UI para escondê-los (visível no Network tab).
            boolean isLocked = !leadsUnlockedByPlan && rs.getTimestamp("contact_revealed_at") == null;
            m.put("isLocked", isLocked);
            m.put("contactName", isLocked ? maskName(rs.getString("contact_name")) : rs.getString("contact_name"));
            m.put("contactEmail", isLocked ? maskEmail(rs.getString("contact_email")) : rs.getString("contact_email"));
            m.put("contactPhone", isLocked ? maskPhone(rs.getString("contact_phone")) : rs.getString("contact_phone"));
            m.put("responseCount", 0);
            m.put("lastResponseAt", null);
            m.put("hasDecisionDossier", false);
            m.put("proposal", null);
            m.put("timeline", List.of());
            m.put("conversation", List.of());

            var stg = rs.getString("stage");

            // slaBucket = tempo desde a última resposta da equipa (ou desde a criação,
            // se ainda ninguém respondeu). Limiar "late" configurável por agência
            // (leadFollowUpHours / proposalFollowUpHours, ver fetchSlaHours()); "fresh"
            // é 1/3 desse limiar, mesma proporção que o WHERE acima — que tem de usar
            // exatamente o mesmo relógio (COALESCE(last_responded_at, created_at)),
            // senão o filtro "Fora do prazo" devolve leads que a lista mostra a verde.
            var createdAt = rs.getTimestamp("created_at").toInstant();
            m.put("createdAt", createdAt.toString());
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
            var lastRespondedTs = rs.getTimestamp("last_responded_at");
            var slaClockFrom = lastRespondedTs != null ? lastRespondedTs.toInstant() : createdAt;
            long ageHours = Duration.between(slaClockFrom, now).toHours();
            int lateHoursForStage = "proposal".equals(stg) ? slaHours.proposalHours() : slaHours.leadHours();
            int freshHoursForStage = Math.max(1, lateHoursForStage / 3);
            String bucket = ageHours < freshHoursForStage ? "fresh" : ageHours < lateHoursForStage ? "attention" : "late";
            m.put("slaBucket", bucket);

            // priority = bucket do LEAD_SCORE (valor), decoupled da idade. Leads
            // fechados (won/lost) nunca são "high" — o score dá-lhes etapa 0, mas
            // forçamos low para a UI nunca marcar um negócio ganho como prioritário.
            boolean closedStage = "won".equals(stg) || "lost".equals(stg);
            int leadScore = rs.getInt("lead_score");
            String priorityBucket = closedStage ? "low"
                : leadScore >= 45 ? "high" : leadScore >= 25 ? "medium" : "low";
            m.put("priority", priorityBucket);

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
                    SELECT lead_id, id, sender_type::text AS sender_type, is_internal, body, created_at
                    FROM properia.chat_messages
                    WHERE lead_id IN (:ids)
                    ORDER BY created_at ASC
                    """)
                .param("ids", leadIds)
                .query((rs, n) -> {
                    var leadId = UUID.fromString(rs.getString("lead_id"));
                    var senderType = rs.getString("sender_type");
                    var createdAt = rs.getTimestamp("created_at").toInstant();
                    // Nota interna é para a equipa — o comprador nunca a vê, logo não é
                    // resposta: não conta para responseCount/lastResponseAt nem para o SLA.
                    // Antes entrava como "Resposta enviada" e dava um lead por respondido.
                    var isInternalNote = rs.getBoolean("is_internal");
                    var direction = "buyer".equals(senderType) ? "inbound"
                        : !"advertiser_member".equals(senderType) ? "internal"
                        : isInternalNote ? "internal" : "outbound";
                    var title = "inbound".equals(direction) ? "Mensagem do comprador"
                        : "outbound".equals(direction) ? "Resposta enviada"
                        : isInternalNote ? "Nota interna" : "Mensagem de sistema";
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

                // Eventos gravados em metadata.events (ex: stage_changed via UpdateLeadStageUseCase)
                @SuppressWarnings("unchecked")
                var itMeta = (Map<String, Object>) it.get("metadata");
                if (itMeta.get("events") instanceof List<?> events) {
                    for (var raw : events) {
                        if (!(raw instanceof Map<?, ?> evMap)) continue;
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("id", evMap.get("id"));
                        entry.put("type", evMap.get("type"));
                        entry.put("title", evMap.get("title"));
                        entry.put("description", evMap.get("description"));
                        entry.put("createdAt", evMap.get("createdAt"));
                        timeline.add(entry);
                    }
                }
                timeline.sort(Comparator.comparing(e -> (String) e.get("createdAt")));

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
        var role = requireCanModifyAssignmentOrStage(advertiserId, claims.userId(), id);

        var stage = (String) body.get("stage");
        var assignedToRaw = body.get("assignedTo");
        UUID assignedTo = assignedToRaw != null ? UUID.fromString(assignedToRaw.toString()) : null;

        updateLeadStage.execute(new UpdateLeadStageUseCase.Command(
            id, advertiserId, stage, assignedTo, null, autoClaimUserIdFor(role, claims.userId())));
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

        // Reatribuir ou mudar de etapa um lead de outro consultor exige owner/admin —
        // ver requireCanModifyAssignmentOrStage. Notas, proposta e dados de contacto
        // continuam abertos a toda a equipa (não é isso que este gate protege).
        String requestorRole = null;
        if (body.containsKey("stage") || body.containsKey("assignedToUserId")) {
            requestorRole = requireCanModifyAssignmentOrStage(advertiserId, claims.userId(), id);
        }

        // Mudança de etapa e/ou motivo de desfecho passam pelo use case, que aplica
        // as guardas de transição (estados terminais) e a obrigatoriedade do motivo.
        if (body.containsKey("stage") || body.containsKey("closeReason")) {
            var stage = body.containsKey("stage") ? (String) body.get("stage") : null;
            var closeReason = body.containsKey("closeReason") ? (String) body.get("closeReason") : null;
            // Se o mesmo PATCH também traz assignedToUserId, esse é aplicado abaixo e manda
            // sobre o auto-claim — por isso não reivindica aqui.
            var autoClaim = body.containsKey("assignedToUserId")
                ? null
                : autoClaimUserIdFor(requestorRole, claims.userId());
            updateLeadStage.execute(new UpdateLeadStageUseCase.Command(
                id, advertiserId, stage, null, closeReason, autoClaim));
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
        boolean leadsUnlockedByPlan = billingService.hasLeadsUnlockedByPlan(advertiserId);
        var lead = jdbc.sql("""
                SELECT l.*, li.title as listing_title, li.hero_image_url as listing_hero_image,
                       li.owner_user_id AS listing_owner_user_id
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
                boolean isLocked = !leadsUnlockedByPlan && rs.getTimestamp("contact_revealed_at") == null;
                m.put("isLocked", isLocked);
                m.put("contactName", isLocked ? maskName(rs.getString("contact_name")) : rs.getString("contact_name"));
                m.put("contactEmail", isLocked ? maskEmail(rs.getString("contact_email")) : rs.getString("contact_email"));
                m.put("contactPhone", isLocked ? maskPhone(rs.getString("contact_phone")) : rs.getString("contact_phone"));
                m.put("stage", rs.getString("stage"));
                m.put("source", rs.getString("source"));
                m.put("internalNotes", null);
                m.put("assignedToUserId", rs.getString("assigned_to"));
                m.put("listingOwnerUserId", rs.getString("listing_owner_user_id"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));

        // Mesmo critério da listagem: "meu" = atribuído a mim OU de um imóvel meu.
        // Sem o segundo caso, abrir um lead que aparece na lista dava 404.
        var selfId = claims.userId().toString();
        boolean isOwnLead = selfId.equals(lead.get("assignedToUserId"))
            || selfId.equals(lead.get("listingOwnerUserId"));
        if (isScopedToSelf(advertiserId, claims.userId()) && !isOwnLead) {
            throw new DomainException("NOT_FOUND", "Lead não encontrado.", 404);
        }
        // Campo interno de autorização — não faz parte do contrato do FE.
        lead.remove("listingOwnerUserId");
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

    private record SlaHours(int leadHours, int proposalHours) {}

    /**
     * Limiares de SLA configurados pela agência (modal "Mensagens prontas a usar",
     * properia.advertisers.settings->>'leadFollowUpHours'/'proposalFollowUpHours').
     * Antes eram 24h/72h fixos no código, ignorando por completo o que o
     * owner/admin configurava — o formulário gravava valores que nada lia.
     */
    private SlaHours fetchSlaHours(UUID advertiserId) {
        return jdbc.sql("""
                SELECT COALESCE((settings->>'leadFollowUpHours')::int, 6) AS lead_hours,
                       COALESCE((settings->>'proposalFollowUpHours')::int, 48) AS proposal_hours
                FROM properia.advertisers WHERE id = :adv
                """).param("adv", advertiserId)
            .query((rs, n) -> new SlaHours(rs.getInt("lead_hours"), rs.getInt("proposal_hours")))
            .optional().orElse(new SlaHours(6, 48));
    }

    private static final java.util.Set<String> ROLES_ALLOWED_TO_MODIFY_ANY_LEAD = java.util.Set.of("owner", "admin");

    /** Papéis operacionais que "trabalham a fila" e por isso reivindicam o lead ao mexer-lhe. */
    private static final java.util.Set<String> ROLES_THAT_AUTO_CLAIM = java.util.Set.of("sales");

    /**
     * Auto-claim só para quem trabalha a fila (sales). owner/admin fazem triagem e
     * acompanhamento sobre leads de toda a agência — atribuí-los automaticamente a si
     * próprios ao mudar uma etapa roubaria leads à equipa sem intenção nenhuma.
     */
    private UUID autoClaimUserIdFor(String requestorRole, UUID requestorUserId) {
        return ROLES_THAT_AUTO_CLAIM.contains(requestorRole) ? requestorUserId : null;
    }

    /**
     * Quem pode reatribuir ou mudar o estágio de um lead:
     *   - owner/admin                          → qualquer lead da agência;
     *   - o consultor atribuído (assigned_to)  → o seu lead;
     *   - o angariador do imóvel (owner_user_id) → leads gerados no imóvel dele, mesmo
     *     que o lead ainda não lhe esteja atribuído (são campos independentes: atribuir
     *     um imóvel NÃO atribui os leads que ele gera);
     *   - qualquer membro da agência           → leads ainda por atribuir (assigned_to
     *     NULL), que formam a fila geral / pool de SDR. Deliberado: bloquear isto
     *     impediria "pegar" num lead novo. Quem lhe mexe na etapa reivindica-o
     *     automaticamente (ver auto-claim em UpdateLeadStageUseCase).
     *
     * Devolve o papel do requerente, para o chamador decidir sobre auto-claim sem
     * repetir a query.
     */
    private String requireCanModifyAssignmentOrStage(UUID advertiserId, UUID requestorUserId, UUID leadId) {
        var requestorRole = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", requestorUserId)
            .query(String.class).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem permissão para este lead.", 403));

        if (ROLES_ALLOWED_TO_MODIFY_ANY_LEAD.contains(requestorRole)) return requestorRole;

        var lead = jdbc.sql("""
                SELECT l.assigned_to, li.owner_user_id
                FROM properia.leads l
                LEFT JOIN properia.listings li ON li.id = l.listing_id
                WHERE l.id = :id AND l.advertiser_id = :adv
                """)
            .param("id", leadId).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("assignedTo", rs.getString("assigned_to"));
                m.put("listingOwner", rs.getString("owner_user_id"));
                return m;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Lead não encontrado.", 404));

        var assignedTo = (String) lead.get("assignedTo");
        var listingOwner = (String) lead.get("listingOwner");
        var selfId = requestorUserId.toString();

        boolean unassigned = assignedTo == null;              // fila geral — livre para quem pegar
        boolean isAssignee = selfId.equals(assignedTo);
        boolean isListingOwner = selfId.equals(listingOwner);

        if (unassigned || isAssignee || isListingOwner) return requestorRole;

        throw new DomainException("FORBIDDEN",
            "Este lead está atribuído a outro consultor. Só owner ou admin podem reatribuí-lo ou mudar o seu estágio.", 403);
    }

    // Sales só vê os seus próprios dados (leads/visitas); owner/admin/editor veem
    // tudo. Não usar para "viewer" (esse é leitura total, sem escrita).
    private boolean isScopedToSelf(UUID advertiserId, UUID userId) {
        var role = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query(String.class).optional().orElse(null);
        return "sales".equals(role);
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }

    // ── Mascaramento de contacto (leads bloqueados) ─────────────────────────────
    // Porto fiel de maskName/maskEmail/maskPhone do frontend (advertiser-leads-page.tsx),
    // para o "teaser" continuar igual mas sem nunca sair PII em claro do servidor para
    // quem não tem direito a vê-la.

    private static String maskName(String name) {
        if (name == null || name.isBlank()) return "Nome protegido";
        var parts = name.strip().split("\\s+");
        if (parts.length == 1) return maskWord(parts[0]);
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) sb.append(' ').append(maskWord(parts[i]));
        return sb.toString();
    }

    private static String maskWord(String word) {
        int dots = Math.max(2, Math.min(5, word.length() - 1));
        return word.substring(0, 1) + "•".repeat(dots);
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "Email protegido";
        int atIdx = email.indexOf('@');
        if (atIdx < 0) return "Email protegido";
        var local = email.substring(0, atIdx);
        var domain = email.substring(atIdx + 1);
        int dotIdx = domain.indexOf('.');
        var domainName = dotIdx < 0 ? domain : domain.substring(0, dotIdx);
        var tld = dotIdx < 0 ? "" : domain.substring(dotIdx + 1);
        var safeLocal = local.length() <= 2
            ? (local.isEmpty() ? "•" : local.substring(0, 1)) + "•"
            : local.substring(0, 2) + "•••";
        var safeDomain = domainName.length() <= 2
            ? (domainName.isEmpty() ? "•" : domainName.substring(0, 1)) + "•"
            : domainName.substring(0, 2) + "•••";
        return safeLocal + "@" + safeDomain + (tld.isBlank() ? "" : "." + tld);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "Telefone protegido";
        var digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) return "Telefone protegido";
        return "+" + digits.substring(0, 3) + " ••• ••• " + digits.substring(digits.length() - 3);
    }
}
