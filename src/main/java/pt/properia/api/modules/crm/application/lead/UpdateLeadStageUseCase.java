package pt.properia.api.modules.crm.application.lead;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UpdateLeadStageUseCase {

    private static final Set<String> VALID_STAGES =
        Set.of("new", "contacted", "qualified", "visit_scheduled", "proposal", "won", "lost");

    private static final Set<String> TERMINAL_STAGES = Set.of("won", "lost");

    private static final Set<String> VALID_CLOSE_REASONS =
        Set.of("price", "financing", "timing", "location", "competitor", "documentation", "inventory_unavailable", "other");

    private static final Map<String, String> STAGE_LABELS = Map.of(
        "new", "Novo",
        "contacted", "Contactado",
        "qualified", "Qualificado",
        "visit_scheduled", "Visita agendada",
        "proposal", "Proposta",
        "won", "Ganho",
        "lost", "Perdido"
    );

    private final LeadJpaRepository leadRepo;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    public UpdateLeadStageUseCase(LeadJpaRepository leadRepo, ObjectMapper objectMapper, JdbcClient jdbc) {
        this.leadRepo = leadRepo;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /**
     * @param autoClaimUserId quando não-nulo, o lead é reivindicado por este utilizador se
     *        estiver na fila geral (assigned_to NULL) e a etapa mudar — ver auto-claim em
     *        execute(). O chamador (LeadController) só o preenche para papéis operacionais
     *        que trabalham a fila; owner/admin fazem triagem sem tomar posse.
     */
    public record Command(UUID leadId, UUID advertiserId, String stage, UUID assignedTo,
                          String closeReason, UUID autoClaimUserId) {

        /** Overload para chamadores que não participam no auto-claim. */
        public Command(UUID leadId, UUID advertiserId, String stage, UUID assignedTo, String closeReason) {
            this(leadId, advertiserId, stage, assignedTo, closeReason, null);
        }
    }

    public Lead execute(Command cmd) {
        var lead = leadRepo.findByIdAndAdvertiserId(cmd.leadId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Lead não encontrado."));

        var changingStage = cmd.stage() != null && !cmd.stage().equals(lead.getStage());

        if (changingStage) {
            if (!VALID_STAGES.contains(cmd.stage())) {
                throw new DomainException("VALIDATION_ERROR", "Etapa inválida: " + cmd.stage());
            }
            if (TERMINAL_STAGES.contains(lead.getStage())) {
                throw new DomainException("VALIDATION_ERROR",
                    "Este lead já está fechado e não pode ser reaberto.", 422);
            }
        }

        var targetStage = cmd.stage() != null ? cmd.stage() : lead.getStage();
        if (changingStage && TERMINAL_STAGES.contains(targetStage)) {
            var reason = cmd.closeReason() != null ? cmd.closeReason() : extractCloseReason(lead.getMetadata());
            if (reason == null || reason.isBlank()) {
                throw new DomainException("VALIDATION_ERROR",
                    "Indica o motivo do desfecho antes de fechar o lead.", 422);
            }
            if (!VALID_CLOSE_REASONS.contains(reason)) {
                throw new DomainException("VALIDATION_ERROR", "Motivo de desfecho inválido.", 422);
            }
        }

        if (cmd.stage() != null) {
            // Regista o evento ANTES de sobrescrever o stage — precisamos do valor antigo
            // para a transição "De -> Para" ficar na timeline (advertiser-leads-page.tsx).
            // Só aplica-se a mudanças manuais (consultor); o avanço automático via chat/visita
            // (LeadStageAdvancer) não passa por aqui e não emite este evento.
            if (changingStage) {
                lead.setMetadata(appendStageChangedEvent(lead.getMetadata(), lead.getStage(), cmd.stage()));
            }
            lead.setStage(cmd.stage());
        }
        if (cmd.assignedTo() != null) {
            lead.setAssignedTo(cmd.assignedTo());
        }

        // ── Auto-claim ("first to claim") ────────────────────────────────────────
        // Um lead sem responsável (assigned_to NULL) está na fila geral/pool de SDR.
        // Quem lhe mexer na etapa passa a ser o responsável — evita que leads
        // trabalhados fiquem eternamente sem dono e que dois consultores lhes peguem
        // ao mesmo tempo. Só se aplica quando ninguém foi explicitamente indicado no
        // pedido (cmd.assignedTo() == null): uma reatribuição manual manda sempre.
        boolean autoClaimed = changingStage
            && cmd.assignedTo() == null
            && cmd.autoClaimUserId() != null
            && lead.getAssignedTo() == null;
        if (autoClaimed) {
            lead.setAssignedTo(cmd.autoClaimUserId());
            lead.setMetadata(appendAutoClaimEvent(lead.getMetadata()));
        }

        if (cmd.closeReason() != null) {
            lead.setMetadata(mergeCloseReason(lead.getMetadata(), cmd.closeReason()));
        }

        var saved = leadRepo.save(lead);

        if (autoClaimed) {
            writeAutoClaimAudit(cmd.advertiserId(), cmd.autoClaimUserId(), cmd.leadId(), cmd.stage());
        }

        // Fechar o lead (won/lost) encerra a conversa de chat associada: o comprador
        // deixa de poder enviar mensagens e o ciclo fica fechado de ambos os lados.
        if (changingStage && TERMINAL_STAGES.contains(targetStage)) {
            jdbc.sql("""
                    UPDATE properia.chat_conversations
                    SET status = 'closed', closed_at = now()
                    WHERE lead_id = :lid AND status <> 'closed'
                    """)
                .param("lid", cmd.leadId())
                .update();
        }

        return saved;
    }

    /** Acrescenta um evento 'stage_changed' a metadata.events (lido pela timeline em LeadController). */
    @SuppressWarnings("unchecked")
    private String appendStageChangedEvent(String metadataJson, String fromStage, String toStage) {
        try {
            var parsed = metadataJson != null && !metadataJson.isBlank()
                ? new LinkedHashMap<String, Object>(objectMapper.readValue(metadataJson, Map.class))
                : new LinkedHashMap<String, Object>();

            var events = new ArrayList<Object>();
            if (parsed.get("events") instanceof List<?> existing) events.addAll(existing);

            var event = new LinkedHashMap<String, Object>();
            event.put("id", "stage-" + UUID.randomUUID());
            event.put("type", "stage_changed");
            event.put("title", "Mudou de etapa");
            event.put("description",
                STAGE_LABELS.getOrDefault(fromStage, fromStage) + " → " + STAGE_LABELS.getOrDefault(toStage, toStage));
            event.put("createdAt", Instant.now().toString());
            events.add(event);

            parsed.put("events", events);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            return metadataJson;
        }
    }

    /** Evento visível na timeline do lead (advertiser-leads-page.tsx) — o consultor vê que ficou responsável. */
    @SuppressWarnings("unchecked")
    private String appendAutoClaimEvent(String metadataJson) {
        try {
            var parsed = metadataJson != null && !metadataJson.isBlank()
                ? new LinkedHashMap<String, Object>(objectMapper.readValue(metadataJson, Map.class))
                : new LinkedHashMap<String, Object>();

            var events = new ArrayList<Object>();
            if (parsed.get("events") instanceof List<?> existing) events.addAll(existing);

            var event = new LinkedHashMap<String, Object>();
            event.put("id", "claim-" + UUID.randomUUID());
            event.put("type", "note");
            event.put("title", "Lead reivindicado");
            event.put("description", "Ficou responsável por este lead ao alterar a etapa (estava na fila geral).");
            event.put("createdAt", Instant.now().toString());
            events.add(event);

            parsed.put("events", events);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            return metadataJson;
        }
    }

    /**
     * Trilha de auditoria do CRM (properia.crm_audit_events, exposta em /admin/auditoria).
     * Best-effort: uma falha aqui nunca deve impedir a mudança de etapa.
     */
    private void writeAutoClaimAudit(UUID advertiserId, UUID actorUserId, UUID leadId, String stage) {
        try {
            jdbc.sql("""
                    INSERT INTO properia.crm_audit_events
                      (advertiser_id, actor_user_id, entity_type, lead_id, action, payload)
                    VALUES (:adv, :uid, 'lead', :lid, 'lead_auto_claimed', :payload::jsonb)
                    """)
                .param("adv", advertiserId)
                .param("uid", actorUserId)
                .param("lid", leadId)
                .param("payload", objectMapper.writeValueAsString(Map.of(
                    "reason", "stage_change_on_unassigned_lead",
                    "stage", stage != null ? stage : "")))
                .update();
        } catch (Exception e) {
            // Auditoria é secundária — não falhar a operação principal por causa dela.
        }
    }

    private String extractCloseReason(String metadataJson) {
        try {
            @SuppressWarnings("unchecked")
            var map = objectMapper.readValue(metadataJson, Map.class);
            var v = map.get("closeReason");
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String mergeCloseReason(String metadataJson, String closeReason) {
        try {
            @SuppressWarnings("unchecked")
            var parsed = objectMapper.readValue(metadataJson, Map.class);
            var map = new LinkedHashMap<String, Object>(parsed);
            map.put("closeReason", closeReason);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return metadataJson;
        }
    }
}
