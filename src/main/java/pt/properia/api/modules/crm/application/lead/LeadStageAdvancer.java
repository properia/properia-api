package pt.properia.api.modules.crm.application.lead;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Avanço automático de estágio do lead a partir de eventos (chat, visitas).
 *
 * Regra única: avança SÓ "para a frente" no funil (nunca regride) e nunca mexe em
 * leads fechados (won/lost). A ordem do funil vem de properia.lead_stage_rank(),
 * não da ordem física do enum (que é ilógica — ver V53). Idempotente.
 *
 * Isto é distinto das mudanças manuais de estágio feitas pelo consultor
 * (UpdateLeadStageUseCase), que podem mover em qualquer direção.
 */
@Component
public class LeadStageAdvancer {

    private final JdbcClient jdbc;

    public LeadStageAdvancer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void advanceForward(UUID leadId, UUID advertiserId, String targetStage) {
        if (leadId == null || advertiserId == null || targetStage == null) return;
        jdbc.sql("""
                UPDATE properia.leads
                SET stage = :target::properia.lead_stage, updated_at = now()
                WHERE id = :id AND advertiser_id = :adv
                  AND stage NOT IN ('won', 'lost')
                  AND properia.lead_stage_rank(stage::text) < properia.lead_stage_rank(:target)
                """)
            .param("target", targetStage)
            .param("id", leadId)
            .param("adv", advertiserId)
            .update();
    }
}
