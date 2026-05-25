package pt.properia.api.modules.zone.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fetches nearby POIs from the Overpass API for each zone category.
 *
 * Query construction bug fix: each individual filter line must carry its own
 * (around:RADIUS,lat,lng) constraint. A single %s(around:...) appended at the
 * end of a multi-statement union only constrains the last statement.
 */
@Component
public class OverpassPoiClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassPoiClient.class);
    static final int RADIUS_M = 500;

    private final OverpassProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public OverpassPoiClient(OverpassProperties props, ObjectMapper json) {
        this.props = props;
        this.json  = json;
        this.http  = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(8000))
            .build();
    }

    public record PoiItem(String name, double lat, double lng, double distanceM) {}
    public record CategoryResult(String categoryId, int totalCount, PoiItem nearest) {}

    /** Fetches all 8 zone categories around the given coordinates. */
    public List<CategoryResult> fetchAll(double lat, double lng) {
        var results = new ArrayList<CategoryResult>();
        for (var entry : CATEGORY_FILTERS.entrySet()) {
            try {
                results.add(fetchCategory(entry.getKey(), entry.getValue(), lat, lng));
            } catch (Exception e) {
                log.warn("Overpass fetch failed for category {}: {}", entry.getKey(), e.getMessage());
                results.add(new CategoryResult(entry.getKey(), 0, null));
            }
        }
        return results;
    }

    private CategoryResult fetchCategory(String categoryId, List<String> filters,
                                          double lat, double lng) throws Exception {
        // Each filter line gets its own (around:RADIUS,lat,lng) — this is the critical fix.
        // Previously a single suffix was appended only to the last statement, so all
        // multi-statement categories (transportes, supermercados, saúde, etc.) fetched
        // globally instead of within the radius, returning 0 results.
        var body = filters.stream()
            .map(f -> "  " + f + String.format("(around:%d,%.6f,%.6f)", RADIUS_M, lat, lng) + ";")
            .collect(Collectors.joining("\n"));

        var query = "[out:json][timeout:15];\n(\n" + body + "\n);\nout center tags qt 25;\n";

        var request = HttpRequest.newBuilder()
            .uri(URI.create(props.getUrl()))
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "ProperiaApi/1.0 (zone-processing)")
            .POST(HttpRequest.BodyPublishers.ofString(
                "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Overpass HTTP " + response.statusCode()
                + " for " + categoryId + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        var root     = json.readTree(response.body());
        var elements = root.path("elements");
        int total    = elements.size();
        PoiItem nearest = null;
        double minDist  = Double.MAX_VALUE;

        for (JsonNode el : elements) {
            double eLat = el.has("center") ? el.path("center").path("lat").asDouble()
                                           : el.path("lat").asDouble();
            double eLng = el.has("center") ? el.path("center").path("lon").asDouble()
                                           : el.path("lon").asDouble();
            if (eLat == 0 && eLng == 0) continue;

            double dist = haversineM(lat, lng, eLat, eLng);
            if (dist < minDist) {
                minDist = dist;
                var tags = el.path("tags");
                String name = Optional.ofNullable(tags.path("name").asText(null))
                    .filter(s -> !s.isBlank())
                    .orElse(Optional.ofNullable(tags.path("brand").asText(null))
                        .filter(s -> !s.isBlank())
                        .orElse(null));
                nearest = new PoiItem(name, eLat, eLng, dist);
            }
        }

        log.debug("Overpass {}: {} elements, nearest={}m", categoryId, total,
            nearest != null ? (int) nearest.distanceM() : "-");

        return new CategoryResult(categoryId, total, nearest);
    }

    // ── Category filters — each entry is a list of individual Overpass QL ──────
    // type[tag] expressions (WITHOUT around filter — that is appended per-line
    // in fetchCategory so every statement is properly constrained to the radius).

    private static final Map<String, List<String>> CATEGORY_FILTERS = new LinkedHashMap<>();
    static {
        CATEGORY_FILTERS.put("transportes", List.of(
            "node[\"railway\"~\"subway_entrance|station|halt|tram_stop\"]",
            "node[\"highway\"=\"bus_stop\"]",
            "node[\"public_transport\"~\"stop_position|platform\"][\"railway\"~\"subway|tram|rail\"]",
            "node[\"amenity\"=\"ferry_terminal\"]"
        ));
        CATEGORY_FILTERS.put("supermercados", List.of(
            "node[\"shop\"~\"supermarket|grocery\"]",
            "way[\"shop\"~\"supermarket|grocery\"]",
            "node[\"shop\"=\"convenience\"][\"brand\"]"      // branded convenience stores only
        ));
        CATEGORY_FILTERS.put("saude", List.of(
            "node[\"amenity\"~\"hospital|clinic|doctors|dentist|health_centre\"]",
            "way[\"amenity\"~\"hospital|clinic|health_centre\"]"
        ));
        CATEGORY_FILTERS.put("farmacias", List.of(
            "node[\"amenity\"=\"pharmacy\"]"
        ));
        CATEGORY_FILTERS.put("escolas", List.of(
            "node[\"amenity\"~\"school|kindergarten|university|college\"]",
            "way[\"amenity\"~\"school|kindergarten|university|college\"]"
        ));
        CATEGORY_FILTERS.put("parques", List.of(
            "node[\"leisure\"~\"park|garden|playground\"][\"name\"]",
            "way[\"leisure\"~\"park|garden\"][\"name\"]",
            "node[\"landuse\"=\"recreation_ground\"][\"name\"]"
        ));
        CATEGORY_FILTERS.put("cafes_restauracao", List.of(
            "node[\"amenity\"~\"cafe|restaurant|bar|fast_food|pub|food_court\"]"
        ));
        CATEGORY_FILTERS.put("ginasios", List.of(
            "node[\"leisure\"~\"fitness_centre|sports_centre|sports_hall|swimming_pool\"]",
            "way[\"leisure\"~\"fitness_centre|sports_centre|sports_hall\"]"
        ));
    }

    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double R    = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
