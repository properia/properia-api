package pt.properia.api.modules.zone.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reentrega automaticamente a análise de zona de anúncios publicados cujo snapshot ficou
 * 'error' ou 'not_processed' — tipicamente por falha temporária da Overpass API. Sem isto,
 * uma falha na primeira tentativa só seria retentada se o anunciante voltasse a publicar
 * o anúncio manualmente (PublishListingUseCase.hasSuccessfulZoneSnapshot).
 *
 * Usa o próprio location_snapshot gravado no snapshot (não relê listings/listing_location)
 * para reprocessar exatamente a mesma localização e reaproveitar o fingerprint existente —
 * uma localização editada entretanto já dispara o seu próprio snapshot novo via
 * PatchListingService.
 */
@Component
public class ZoneEnrichmentRetryJob {

    private static final Logger log = LoggerFactory.getLogger(ZoneEnrichmentRetryJob.class);

    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final ZoneSnapshotService zoneSnapshotService;
    private final ObjectMapper json;

    public ZoneEnrichmentRetryJob(JdbcClient jdbc, ZoneSnapshotService zoneSnapshotService, ObjectMapper json) {
        this.jdbc = jdbc;
        this.zoneSnapshotService = zoneSnapshotService;
        this.json = json;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void retryFailedZoneSnapshots() {
        var pending = jdbc.sql("""
                SELECT s.id, s.listing_id, s.location_snapshot::text AS location_snapshot
                FROM properia.listing_zone_snapshots s
                JOIN properia.listings l ON l.id = s.listing_id
                WHERE s.status IN ('error', 'not_processed')
                  AND s.retry_count < :maxAttempts
                  AND (s.last_attempt_at IS NULL OR s.last_attempt_at < now() - interval '10 minutes')
                  AND l.status = 'published'
                ORDER BY s.updated_at ASC
                LIMIT :limit
                """)
            .param("maxAttempts", MAX_ATTEMPTS)
            .param("limit", BATCH_SIZE)
            .query((rs, n) -> new PendingSnapshot(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("listing_id")),
                rs.getString("location_snapshot")))
            .list();

        if (pending.isEmpty()) return;

        int triggered = 0;
        for (var p : pending) {
            try {
                var snap = json.readTree(p.locationSnapshot());
                double lat = snap.get("latitude").asDouble();
                double lng = snap.get("longitude").asDouble();
                zoneSnapshotService.processAsync(
                    p.listingId(), lat, lng,
                    snap.path("street").asText(null),
                    snap.path("neighborhood").asText(null),
                    snap.path("city").asText(null),
                    snap.path("precision").asText(null)
                );
                triggered++;
            } catch (Exception e) {
                log.warn("ZoneEnrichmentRetryJob: falha ao preparar retry do snapshot {}: {}", p.id(), e.getMessage());
            }
        }
        log.info("ZoneEnrichmentRetryJob: {} retry(s) de análise de zona disparado(s).", triggered);
    }

    private record PendingSnapshot(UUID id, UUID listingId, String locationSnapshot) {}
}
