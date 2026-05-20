package pt.properia.api.modules.auth.infrastructure;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class AdvertiserAccessQuery {

    private final EntityManager em;

    AdvertiserAccessQuery(EntityManager em) {
        this.em = em;
    }

    @SuppressWarnings("unchecked")
    List<UUID> findAccessibleAdvertiserIds(UUID userId) {
        return (List<UUID>) em.createNativeQuery("""
            SELECT au.advertiser_id
            FROM properia.advertiser_users au
            JOIN properia.advertisers a ON a.id = au.advertiser_id
            JOIN properia.advertiser_onboarding ao ON ao.advertiser_id = a.id
            WHERE au.user_id = :userId
              AND a.is_active = true
              AND a.verification_status != 'suspended'
              AND (ao.status = 'active'
                OR ao.step_current = 'done'
                OR ao.completed_steps @> '["first_listing"]'::jsonb)
            ORDER BY au.created_at ASC
            LIMIT 10
            """)
            .setParameter("userId", userId)
            .getResultList();
    }
}
