package pt.properia.api.modules.advertiser.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;

import java.util.UUID;

/**
 * Relatório semanal por email para proprietários particulares (private_owner)
 * com anúncios publicados: "o teu anúncio teve X visualizações, Y interessados".
 *
 * É o mecanismo de retenção do plano Starter: traz o proprietário de volta ao
 * painel e cria o momento natural de compra de créditos (há interessados novos
 * cujo contacto está por desbloquear).
 *
 * Só para particulares — agências vivem no CRM diariamente e têm o Pulse;
 * um resumo semanal seria ruído.
 */
@Component
public class WeeklyOwnerReportJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyOwnerReportJob.class);

    private final JdbcClient jdbc;
    private final AuthEmailService emailService;

    public WeeklyOwnerReportJob(JdbcClient jdbc, AuthEmailService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    /** Segunda-feira às 09:00 de Lisboa — início de semana, caixa de entrada fresca. */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Europe/Lisbon")
    public void sendWeeklyReports() {
        var owners = jdbc.sql("""
                SELECT a.id::text AS advertiser_id, u.email, u.full_name
                FROM properia.advertisers a
                JOIN properia.advertiser_users au
                  ON au.advertiser_id = a.id AND au.membership_role = 'owner'
                JOIN properia.app_users u ON u.id = au.user_id
                WHERE a.advertiser_type = 'private_owner'
                  AND a.is_active = true
                  AND u.is_active = true
                  AND EXISTS (
                      SELECT 1 FROM properia.listings l
                      WHERE l.advertiser_id = a.id AND l.status = 'published'
                  )
                """)
            .query((rs, n) -> new OwnerRow(
                rs.getString("advertiser_id"),
                rs.getString("email"),
                rs.getString("full_name")
            )).list();

        if (owners.isEmpty()) return;

        int sent = 0;
        int skipped = 0;
        for (var owner : owners) {
            try {
                var stats = loadWeeklyStats(UUID.fromString(owner.advertiserId()));
                // Semana sem qualquer atividade: não enviar "0 visualizações" —
                // um email vazio desmotiva em vez de reter.
                if (stats.views() == 0 && stats.leads() == 0 && stats.visits() == 0) {
                    skipped++;
                    continue;
                }
                emailService.sendWeeklyOwnerReport(
                    owner.email(),
                    firstName(owner.fullName()),
                    stats.views(),
                    stats.leads(),
                    stats.visits(),
                    stats.publishedListings()
                );
                sent++;
            } catch (Exception e) {
                // Um destinatário com problema não pode travar o lote inteiro
                log.warn("WeeklyOwnerReportJob: falha ao enviar para {}: {}", owner.email(), e.getMessage());
            }
        }
        log.info("WeeklyOwnerReportJob: {} relatório(s) enviado(s), {} sem atividade (não enviados), {} proprietário(s) elegíveis.",
            sent, skipped, owners.size());
    }

    private WeeklyStats loadWeeklyStats(UUID advertiserId) {
        return jdbc.sql("""
                SELECT
                  (SELECT COUNT(*) FROM properia.listing_detail_views dv
                     JOIN properia.listings l ON l.id = dv.listing_id
                    WHERE l.advertiser_id = :adv
                      AND dv.created_at >= now() - interval '7 days')::int AS views7,
                  (SELECT COUNT(*) FROM properia.leads ld
                    WHERE ld.advertiser_id = :adv
                      AND ld.created_at >= now() - interval '7 days')::int AS leads7,
                  (SELECT COUNT(*) FROM properia.visits v
                     JOIN properia.leads ld ON ld.id = v.lead_id
                    WHERE ld.advertiser_id = :adv
                      AND v.created_at >= now() - interval '7 days')::int AS visits7,
                  (SELECT COUNT(*) FROM properia.listings l
                    WHERE l.advertiser_id = :adv AND l.status = 'published')::int AS published
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new WeeklyStats(
                rs.getInt("views7"),
                rs.getInt("leads7"),
                rs.getInt("visits7"),
                rs.getInt("published")
            )).single();
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().split("\\s+")[0];
    }

    private record OwnerRow(String advertiserId, String email, String fullName) {}

    private record WeeklyStats(int views, int leads, int visits, int publishedListings) {}
}
