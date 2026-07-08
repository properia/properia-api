package pt.properia.api.modules.billing.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
public class AdvertiserBillingRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AdvertiserBillingRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record BillingSnapshot(
        String planCode,
        Map<String, Object> billingMetadata,
        int creditBalance
    ) {}

    public BillingSnapshot getSnapshot(UUID advertiserId) {
        return jdbc.sql("""
                SELECT plan_code, billing_metadata::text
                FROM properia.advertisers WHERE id = :id
                """)
            .param("id", advertiserId)
            .query((rs, n) -> {
                var meta = parseMetadata(rs.getString("billing_metadata"));
                int credits = 0;
                if (meta.get("creditBalance") instanceof Number nb) {
                    credits = nb.intValue();
                }
                return new BillingSnapshot(rs.getString("plan_code"), meta, credits);
            })
            .optional()
            .orElse(new BillingSnapshot("free", Map.of(), 0));
    }

    /**
     * Aplica um patch (merge raso de chaves de topo) ao billing_metadata de forma ATÓMICA.
     * Usa `||` (jsonb concat) no próprio UPDATE, com lock de linha do Postgres, em vez do
     * antigo read-modify-write que perdia patches concorrentes (ver correção #1).
     */
    public void patchBillingMetadata(UUID advertiserId, Map<String, Object> patch) {
        try {
            var json = objectMapper.writeValueAsString(patch);
            jdbc.sql("""
                    UPDATE properia.advertisers
                    SET billing_metadata = COALESCE(billing_metadata, '{}'::jsonb) || :patch::jsonb
                    WHERE id = :id
                    """)
                .param("patch", json)
                .param("id", advertiserId)
                .update();
        } catch (Exception e) {
            throw new RuntimeException("Failed to patch billing metadata", e);
        }
    }

    /**
     * Concede créditos de boas-vindas UMA só vez, atomicamente. Devolve o novo saldo se
     * concedeu agora, ou empty se já tinham sido concedidos (guarda idempotente na cláusula
     * WHERE — sem check-then-act, sem lost-update; ver correção #2).
     */
    public java.util.Optional<Integer> grantWelcomeCreditsOnce(UUID advertiserId, int amount, String nowIso) {
        return jdbc.sql("""
                UPDATE properia.advertisers
                SET billing_metadata = jsonb_set(
                        jsonb_set(
                            COALESCE(billing_metadata, '{}'::jsonb),
                            '{creditBalance}',
                            to_jsonb(COALESCE((billing_metadata->>'creditBalance')::int, 0) + :amount)),
                        '{welcomeCreditsGrantedAt}', to_jsonb(:now::text))
                WHERE id = :id
                  AND NOT (COALESCE(billing_metadata, '{}'::jsonb) ? 'welcomeCreditsGrantedAt')
                RETURNING (billing_metadata->>'creditBalance')::int
                """)
            .param("amount", amount)
            .param("now", nowIso)
            .param("id", advertiserId)
            .query(Integer.class)
            .optional();
    }

    /**
     * Debita créditos ATOMICAMENTE, só se houver saldo suficiente — a guarda de saldo
     * vive na cláusula WHERE do próprio UPDATE (não é check-then-act), por isso duas
     * chamadas concorrentes nunca conseguem levar o saldo a negativo. Devolve o novo
     * saldo se debitou, ou empty se o saldo era insuficiente (nada é alterado).
     */
    public java.util.Optional<Integer> spendCreditOnce(UUID advertiserId, int amount) {
        return jdbc.sql("""
                UPDATE properia.advertisers
                SET billing_metadata = jsonb_set(
                        COALESCE(billing_metadata, '{}'::jsonb),
                        '{creditBalance}',
                        to_jsonb(COALESCE((billing_metadata->>'creditBalance')::int, 0) - :amount))
                WHERE id = :id
                  AND COALESCE((billing_metadata->>'creditBalance')::int, 0) >= :amount
                RETURNING (billing_metadata->>'creditBalance')::int
                """)
            .param("amount", amount)
            .param("id", advertiserId)
            .query(Integer.class)
            .optional();
    }

    /**
     * Ativa o trial UMA só vez, atomicamente (plano + metadata num único UPDATE guardado).
     * Devolve true se ativou agora, false se já estava ativo (correção #6).
     */
    public boolean activateTrialOnce(UUID advertiserId, String planCode, Map<String, Object> metaPatch) {
        try {
            var json = objectMapper.writeValueAsString(metaPatch);
            var rows = jdbc.sql("""
                    UPDATE properia.advertisers
                    SET plan_code = :plan,
                        billing_metadata = COALESCE(billing_metadata, '{}'::jsonb) || :patch::jsonb
                    WHERE id = :id
                      AND NOT (COALESCE(billing_metadata, '{}'::jsonb) ? 'trialActivatedAt')
                    """)
                .param("plan", planCode)
                .param("patch", json)
                .param("id", advertiserId)
                .update();
            return rows > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to activate trial", e);
        }
    }

    /**
     * Marca um evento Stripe como processado. Devolve true se foi inserido agora (primeira vez),
     * false se já existia (replay/duplicado) — dedup de webhooks (correção #3).
     */
    public boolean markWebhookProcessed(String eventId, String eventType) {
        if (eventId == null || eventId.isBlank()) return true; // sem id → não deduplicamos
        var inserted = jdbc.sql("""
                INSERT INTO properia.stripe_webhook_events (event_id, event_type)
                VALUES (:id, :type)
                ON CONFLICT (event_id) DO NOTHING
                """)
            .param("id", eventId)
            .param("type", eventType)
            .update();
        return inserted > 0;
    }

    /**
     * Guarda o stripeCustomerId apenas se ainda não existir (claim atómico) e devolve o id
     * efetivo — o nosso se ganhámos, ou o já existente se outra thread ganhou. Evita que dois
     * checkouts concorrentes fixem clientes Stripe diferentes; o cliente perdedor fica órfão
     * mas inerte (correção #7).
     */
    public String claimStripeCustomerId(UUID advertiserId, String candidateId) {
        return jdbc.sql("""
                WITH upd AS (
                    UPDATE properia.advertisers
                    SET billing_metadata = jsonb_set(
                            COALESCE(billing_metadata, '{}'::jsonb),
                            '{stripeCustomerId}', to_jsonb(:cid::text))
                    WHERE id = :id
                      AND NOT (COALESCE(billing_metadata, '{}'::jsonb) ? 'stripeCustomerId')
                    RETURNING billing_metadata->>'stripeCustomerId' AS cid
                )
                SELECT cid FROM upd
                UNION ALL
                SELECT billing_metadata->>'stripeCustomerId'
                FROM properia.advertisers WHERE id = :id
                LIMIT 1
                """)
            .param("cid", candidateId)
            .param("id", advertiserId)
            .query(String.class)
            .single();
    }

    public void updatePlanCode(UUID advertiserId, String planCode) {
        jdbc.sql("UPDATE properia.advertisers SET plan_code = :plan WHERE id = :id")
            .param("plan", planCode)
            .param("id", advertiserId)
            .update();
    }

    public void addCreditTransaction(UUID advertiserId, String type, int amount,
                                     int balanceAfter, String stripeSessionId, String description) {
        jdbc.sql("""
                INSERT INTO properia.advertiser_credit_transactions
                  (advertiser_id, type, amount, balance_after, stripe_checkout_session_id, description)
                VALUES (:advertiserId, :type, :amount, :balanceAfter, :sessionId, :description)
                """)
            .param("advertiserId", advertiserId)
            .param("type", type)
            .param("amount", amount)
            .param("balanceAfter", balanceAfter)
            .param("sessionId", stripeSessionId)
            .param("description", description)
            .update();
    }

    public int getCreditBalance(UUID advertiserId) {
        return getSnapshot(advertiserId).creditBalance();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
