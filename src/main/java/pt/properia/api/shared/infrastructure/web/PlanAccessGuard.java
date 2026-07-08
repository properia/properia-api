package pt.properia.api.shared.infrastructure.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.properia.api.shared.domain.DomainException;

import java.util.Set;
import java.util.UUID;

/**
 * Gate de plano server-side. Espelha os conjuntos do frontend
 * (shared/advertiser-plan-access.ts) para que a regra não viva só na UI:
 *   - features Pro     → pro | business | pilot
 *   - features Business → business
 *
 * Sem isto, um anunciante Starter poderia chamar diretamente os endpoints Pro/Business
 * (ex.: /api/advertiser/buyers, /api/advertiser/copiloto) e usar funcionalidades pagas.
 */
@Component
public class PlanAccessGuard {

    private static final Set<String> PRO_AND_UP = Set.of("pro", "business", "pilot");
    private static final Set<String> BUSINESS_ONLY = Set.of("business");

    private final JdbcClient jdbc;

    public PlanAccessGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireProFeatures(UUID advertiserId) {
        requirePlanIn(advertiserId, PRO_AND_UP,
            "Esta funcionalidade requer o plano Pro ou superior.");
    }

    public void requireBusinessFeatures(UUID advertiserId) {
        requirePlanIn(advertiserId, BUSINESS_ONLY,
            "Esta funcionalidade requer o plano Business.");
    }

    private void requirePlanIn(UUID advertiserId, Set<String> allowed, String message) {
        var planCode = jdbc.sql("SELECT plan_code FROM properia.advertisers WHERE id = :id")
            .param("id", advertiserId)
            .query(String.class)
            .optional()
            .orElse(null);

        if (planCode == null || !allowed.contains(planCode)) {
            // 402 Payment Required: o cliente sabe que é uma barreira de plano (upsell), não um 403 de auth.
            throw new DomainException("PLAN_UPGRADE_REQUIRED", message, 402);
        }
    }
}
