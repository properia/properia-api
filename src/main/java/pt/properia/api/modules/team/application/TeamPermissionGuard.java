package pt.properia.api.modules.team.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.properia.api.shared.domain.DomainException;

import java.util.Set;
import java.util.UUID;

/**
 * Único portão de "és owner ou admin desta equipa?" — antes desta classe, cada
 * endpoint de gestão de equipa reimplementava (ou esquecia de reimplementar) a
 * mesma verificação. {@code addMemberByEmail} tinha-a; updateMemberRole,
 * removeMember, createInvite, cancelInvite e resendInvite não tinham nenhuma:
 * qualquer membro autenticado da conta — incluindo um viewer, papel de leitura —
 * conseguia promover colegas, remover membros e gerir convites só por chamar a
 * API directamente, sem passar pela UI que escondia esses botões.
 */
@Component
public class TeamPermissionGuard {

    private static final Set<String> MANAGE_ROLES = Set.of("owner", "admin");

    private final JdbcClient jdbc;

    public TeamPermissionGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws DomainException 403 se o utilizador não pertencer à conta, ou pertencer com um papel que não gere equipa. */
    public void requireOwnerOrAdmin(UUID advertiserId, UUID userId) {
        var role = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """)
            .param("adv", advertiserId)
            .param("uid", userId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem permissão.", 403));

        if (!MANAGE_ROLES.contains(role)) {
            throw new DomainException("FORBIDDEN", "Apenas owner ou admin podem gerir a equipa.", 403);
        }
    }

    /**
     * Única implementação do tecto de vagas por plano — antes desta extracção só
     * corria em TeamController (ao criar convite), nunca ao aceitar um convite já
     * existente. Isso deixava uma janela real: se o plano baixasse de escalão
     * enquanto havia convites pendentes desse período mais generoso, aceitá-los
     * depois nunca era recusado, e a equipa ficava permanentemente acima do tecto
     * do novo plano — sem que nada alguma vez a corrigisse.
     *
     * @throws DomainException 403 se a conta já estiver no limite de membros do plano efectivo.
     */
    public void requireSeatAvailable(UUID advertiserId) {
        // Resolve o plano efectivo (respeita trial activo).
        var row = jdbc.sql("""
                SELECT plan_code,
                       billing_metadata->>'trialActivatedAt' AS trial_activated_at,
                       billing_metadata->>'trialEndsAt'      AS trial_ends_at
                FROM properia.advertisers WHERE id = :id
                """).param("id", advertiserId)
            .query((rs, n) -> new String[]{
                rs.getString("plan_code"),
                rs.getString("trial_activated_at"),
                rs.getString("trial_ends_at")
            }).optional().orElse(new String[]{"starter", null, null});

        String planCode = row[0] != null ? row[0] : "starter";

        if (row[1] != null && row[2] != null) {
            try {
                var endsAt = java.time.Instant.parse(row[2]);
                if (java.time.Instant.now().isBefore(endsAt)) {
                    planCode = "business";
                }
            } catch (Exception ignored) {
                // Timestamp malformado nos metadados — trata como sem trial activo.
            }
        }

        // pilot é sempre ilimitado, tal como business — nunca deve ficar preso a
        // um tecto artificial nem aqui nem na UI (ver AdvertiserBillingController).
        int maxMembers = switch (planCode) {
            case "business", "pilot" -> -1;
            case "pro" -> 5;
            default -> 1; // starter
        };

        if (maxMembers == -1) return;

        long current = jdbc.sql("""
                SELECT COUNT(*) FROM properia.advertiser_users WHERE advertiser_id = :adv
                """).param("adv", advertiserId).query(Long.class).single();

        if (current >= maxMembers) {
            throw new DomainException("PLAN_LIMIT_EXCEEDED",
                "O plano da equipa já atingiu o limite de vagas disponíveis.", 403);
        }
    }
}
