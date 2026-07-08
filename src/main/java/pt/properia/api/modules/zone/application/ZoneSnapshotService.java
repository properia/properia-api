package pt.properia.api.modules.zone.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.zone.infrastructure.OverpassPoiClient;

import java.time.Instant;
import java.util.*;

/**
 * Processes zone data for a listing by fetching nearby POIs from Overpass,
 * stores the result in listing_zone_snapshots, and updates listing_zone_scores
 * with a summary label used in search results.
 */
@Service
public class ZoneSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ZoneSnapshotService.class);
    private static final int RADIUS_M = 500;

    private final OverpassPoiClient overpassClient;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ZoneSnapshotService(OverpassPoiClient overpassClient, JdbcClient jdbc, ObjectMapper json) {
        this.overpassClient = overpassClient;
        this.jdbc           = jdbc;
        this.json           = json;
    }

    /**
     * Triggers zone processing asynchronously after listing publish.
     * Uses virtual threads (Spring Boot 3.2+ with virtual threads enabled).
     */
    @Async
    public void processAsync(UUID listingId, double lat, double lng,
                              String street, String neighborhood, String city,
                              String precision) {
        try {
            process(listingId, lat, lng, street, neighborhood, city, precision);
        } catch (Exception e) {
            log.error("Zone processing failed for listing {}: {}", listingId, e.getMessage(), e);
            markError(listingId, lat, lng, "PROCESSING_FAILED", e.getMessage());
        }
    }

    private void process(UUID listingId, double lat, double lng,
                          String street, String neighborhood, String city,
                          String precision) throws Exception {
        var fingerprint = buildFingerprint(lat, lng);

        // Check for existing up-to-date snapshot
        var existing = jdbc.sql("""
            SELECT id, status FROM properia.listing_zone_snapshots
            WHERE listing_id = :lid AND location_fingerprint = :fp
            ORDER BY created_at DESC LIMIT 1
            """)
            .param("lid", listingId).param("fp", fingerprint)
            .query((rs, n) -> Map.of("id", rs.getString("id"), "status", rs.getString("status")))
            .optional();

        if (existing.isPresent() && "processed".equals(existing.get().get("status"))) {
            // Mark stale so it is eligible for reprocessing on next trigger
            jdbc.sql("UPDATE properia.listing_zone_snapshots SET status = 'stale', updated_at = now() WHERE id = :id")
                .param("id", UUID.fromString((String) existing.get().get("id"))).update();
        }

        var snapshotId = existing.isPresent()
            ? UUID.fromString((String) existing.get().get("id"))
            : insertPendingSnapshot(listingId, fingerprint, lat, lng, street, neighborhood, city, precision);

        // Claim atómico do processamento: só um trigger fica com o snapshot em 'processing'.
        // Se outro já o está a processar (e não está preso há >5 min), este sai sem repetir a
        // chamada externa ao Overpass (correção #9). O guard de staleness permite recuperar de
        // um processamento anterior que tenha crashado.
        var claimed = jdbc.sql("""
                UPDATE properia.listing_zone_snapshots
                SET status = 'processing', last_attempt_at = now(), updated_at = now()
                WHERE id = :id
                  AND (status <> 'processing' OR last_attempt_at < now() - interval '5 minutes')
                RETURNING id
                """)
            .param("id", snapshotId)
            .query(UUID.class)
            .optional();

        if (claimed.isEmpty()) {
            log.info("Zone snapshot {} já está a ser processado por outro trigger — a saltar.", snapshotId);
            return;
        }

        // Fetch POIs from Overpass
        var results = overpassClient.fetchAll(lat, lng);

        // Build payload matching ListingZoneSummary contract
        var locationSnapshot = Map.of(
            "latitude",     lat,
            "longitude",    lng,
            "street",       street != null ? street : "",
            "neighborhood", neighborhood != null ? neighborhood : "",
            "city",         city != null ? city : "",
            "precision",    normalizePrecision(precision)
        );

        var categories = new ArrayList<Map<String, Object>>();
        int totalPois = 0;
        for (var result : results) {
            totalPois += result.totalCount();
            var cat = new LinkedHashMap<String, Object>();
            cat.put("category", result.categoryId());
            cat.put("label", CATEGORY_LABELS.getOrDefault(result.categoryId(), result.categoryId()));
            cat.put("totalCount", result.totalCount());
            cat.put("nearestDistanceM", result.nearest() != null ? Math.round(result.nearest().distanceM()) : null);
            cat.put("nearestWalkingMinutes", result.nearest() != null ? walkingMinutes(result.nearest().distanceM()) : null);

            var topPois = new ArrayList<Map<String, Object>>();
            if (result.nearest() != null) {
                var poi = new LinkedHashMap<String, Object>();
                poi.put("name",           result.nearest().name() != null ? result.nearest().name() : CATEGORY_LABELS.getOrDefault(result.categoryId(), result.categoryId()));
                poi.put("category",       result.categoryId());
                poi.put("distanceM",      Math.round(result.nearest().distanceM()));
                poi.put("walkingMinutes", walkingMinutes(result.nearest().distanceM()));
                poi.put("latitude",       result.nearest().lat());
                poi.put("longitude",      result.nearest().lng());
                poi.put("publicMetadata", Map.of());
                topPois.add(poi);
            }
            cat.put("topPois", topPois);
            categories.add(cat);
        }

        var summaryShort = buildSummaryShort(results);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("listingId",       listingId.toString());
        payload.put("radiusM",         RADIUS_M);
        payload.put("source",          "overpass");
        payload.put("sourceVersion",   1);
        payload.put("processedAt",     Instant.now().toString());
        payload.put("status",          "processed");
        payload.put("summaryShort",    summaryShort);
        payload.put("summaryLong",     null);
        payload.put("dataQuality",     totalPois >= 10 ? "high" : totalPois >= 4 ? "medium" : totalPois > 0 ? "low" : "none");
        payload.put("categories",      categories);
        payload.put("locationSnapshot", locationSnapshot);

        var payloadJson = json.writeValueAsString(payload);

        jdbc.sql("""
            UPDATE properia.listing_zone_snapshots
            SET status = 'processed', payload = :payload::jsonb,
                summary_short = :summary, processed_at = now(), updated_at = now(),
                retry_count = 0
            WHERE id = :id
            """)
            .param("id", snapshotId)
            .param("payload", payloadJson)
            .param("summary", summaryShort)
            .update();

        // Update search-facing zone_scores label
        jdbc.sql("""
            INSERT INTO properia.listing_zone_scores (listing_id, zone_label_primary, zone_summary_short, updated_at)
            VALUES (:lid, :label, :summary, now())
            ON CONFLICT (listing_id) DO UPDATE SET
              zone_label_primary  = EXCLUDED.zone_label_primary,
              zone_summary_short  = EXCLUDED.zone_summary_short,
              updated_at          = now()
            """)
            .param("lid", listingId)
            .param("label", buildZoneLabel(results))
            .param("summary", summaryShort)
            .update();

        log.info("Zone snapshot processed for listing {} ({} POIs)", listingId, totalPois);
    }

    private UUID insertPendingSnapshot(UUID listingId, String fingerprint,
                                        double lat, double lng,
                                        String street, String neighborhood, String city,
                                        String precision) throws Exception {
        var locationSnapshot = json.writeValueAsString(Map.of(
            "latitude", lat, "longitude", lng,
            "street", street != null ? street : "",
            "neighborhood", neighborhood != null ? neighborhood : "",
            "city", city != null ? city : "",
            "precision", normalizePrecision(precision)
        ));
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO properia.listing_zone_snapshots
              (id, listing_id, provider, provider_version, radius_m,
               taxonomy_version, contract_version,
               location_fingerprint, location_snapshot, status, payload,
               created_at, updated_at)
            VALUES (:id, :lid, 'overpass', 1, :radius,
                    1, 1, :fp, :locSnap::jsonb, 'not_processed', '{}'::jsonb, now(), now())
            ON CONFLICT (listing_id, location_fingerprint, contract_version) DO NOTHING
            """)
            .param("id", id)
            .param("lid", listingId)
            .param("radius", RADIUS_M)
            .param("fp", fingerprint)
            .param("locSnap", locationSnapshot)
            .update();
        // In case of conflict, fetch the existing id
        return jdbc.sql("SELECT id FROM properia.listing_zone_snapshots WHERE listing_id = :lid AND location_fingerprint = :fp ORDER BY created_at DESC LIMIT 1")
            .param("lid", listingId).param("fp", fingerprint)
            .query(UUID.class).single();
    }

    private void markError(UUID listingId, double lat, double lng, String code, String message) {
        try {
            jdbc.sql("""
                UPDATE properia.listing_zone_snapshots
                SET status = 'error', error_code = :code, error_message = :msg,
                    retry_count = retry_count + 1, updated_at = now()
                WHERE listing_id = :lid AND location_fingerprint = :fp
                  AND status IN ('not_processed','processing')
                """)
                .param("lid", listingId)
                .param("fp", buildFingerprint(lat, lng))
                .param("code", code)
                .param("msg", message != null ? message.substring(0, Math.min(500, message.length())) : null)
                .update();
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String buildFingerprint(double lat, double lng) {
        return String.format("%.5f:%.5f:500", lat, lng);
    }

    private static Integer walkingMinutes(double distanceM) {
        int mins = (int) Math.ceil(distanceM / 80.0);
        return Math.max(1, mins);
    }

    private static String buildSummaryShort(List<OverpassPoiClient.CategoryResult> results) {
        var highlights = new ArrayList<String>();
        for (var r : results) {
            if (r.nearest() == null) continue;
            double d = r.nearest().distanceM();
            if (d <= 200) {
                highlights.add(CATEGORY_LABELS.getOrDefault(r.categoryId(), r.categoryId()) + " a " + Math.round(d) + "m");
                if (highlights.size() >= 3) break;
            }
        }
        return highlights.isEmpty()
            ? "Zona residencial com serviços próximos."
            : String.join(" · ", highlights) + ".";
    }

    /**
     * Rótulo FACTUAL e verificável em vez de juízo de valor. Os rótulos antigos ("Zona
     * muito bem servida", "bem servida") eram afirmações avaliativas geradas pelo Properia
     * (não pelo anunciante) sobre dados OpenStreetMap não verificados — risco de prática
     * comercial enganosa (DL 57/2008). Passamos a afirmar apenas o que é contável e
     * confirmável: nº de tipos de serviço com POIs no raio de 500 m. Ver migração V58
     * (backfill dos rótulos avaliativos já existentes).
     */
    private static String buildZoneLabel(List<OverpassPoiClient.CategoryResult> results) {
        long categoriesWithPois = results.stream().filter(r -> r.totalCount() > 0).count();
        if (categoriesWithPois <= 0) return "Zona residencial";
        if (categoriesWithPois == 1) return "1 tipo de serviço a menos de 500 m";
        return categoriesWithPois + " tipos de serviço a menos de 500 m";
    }

    private static String normalizePrecision(String precision) {
        if (precision == null) return "neighborhood";
        return switch (precision) {
            case "exact", "street" -> "street";
            case "neighborhood"    -> "neighborhood";
            case "parish"          -> "parish";
            default                -> "municipality";
        };
    }

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
        "transportes",       "Transportes",
        "supermercados",     "Supermercados",
        "saude",             "Saúde",
        "farmacias",         "Farmácias",
        "escolas",           "Escolas",
        "parques",           "Parques",
        "cafes_restauracao", "Cafés e Restauração",
        "ginasios",          "Ginásios"
    );
}
