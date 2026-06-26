package pt.properia.api.modules.crm.application.visit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Visitas com horário passado que nunca foram confirmadas, canceladas nem tiveram
 * um outcome registado ficavam para sempre em 'requested'/'confirmed' (nenhum processo
 * as resolvia). Em vez de assumir que aconteceram ('completed', o que inflacionaria o
 * funil/conversão) ou que falharam ('no_show', injusto para o agente sem confirmação),
 * marcamos como 'expired' — um estado neutro de "precisa de revisão" que o agente pode
 * corrigir manualmente para o desfecho real.
 */
@Component
public class VisitExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(VisitExpiryJob.class);
    private static final String GRACE_INTERVAL = "2 hours";
    private static final String DEFAULT_VISIT_DURATION = "60 minutes";

    private final JdbcClient jdbc;

    public VisitExpiryJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void expireStaleVisits() {
        var updated = jdbc.sql("""
                UPDATE properia.visits
                SET status = 'expired'::properia.visit_status,
                    status_reason = 'auto_expired_no_resolution',
                    updated_at = now()
                WHERE status IN ('requested', 'confirmed')
                  AND COALESCE(ends_at, starts_at + interval '%s') < now() - interval '%s'
                """.formatted(DEFAULT_VISIT_DURATION, GRACE_INTERVAL))
            .update();

        if (updated > 0) {
            log.info("VisitExpiryJob: {} visita(s) marcada(s) como 'expired' por falta de resolução.", updated);
        }
    }
}
