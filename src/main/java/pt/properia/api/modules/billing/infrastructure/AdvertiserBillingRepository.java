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

    public void patchBillingMetadata(UUID advertiserId, Map<String, Object> patch) {
        try {
            var current = getSnapshot(advertiserId).billingMetadata();
            var merged = new java.util.LinkedHashMap<>(current);
            merged.putAll(patch);
            var json = objectMapper.writeValueAsString(merged);
            jdbc.sql("UPDATE properia.advertisers SET billing_metadata = :meta::jsonb WHERE id = :id")
                .param("meta", json)
                .param("id", advertiserId)
                .update();
        } catch (Exception e) {
            throw new RuntimeException("Failed to patch billing metadata", e);
        }
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
