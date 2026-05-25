package pt.properia.api.modules.zone.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Fetches nearby POIs from the Overpass API for each zone category.
 * Results are used to populate listing_zone_snapshots.
 */
@Component
public class OverpassPoiClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassPoiClient.class);
    private static final int RADIUS_M = 500;

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

    public record CategoryResult(String categoryId, int totalCount,
                                  PoiItem nearest) {}

    /**
     * Fetches POIs for all 8 zone categories around the given coordinates.
     * Returns an empty list for a category if the call fails (non-fatal).
     */
    public List<CategoryResult> fetchAll(double lat, double lng) {
        var results = new ArrayList<CategoryResult>();
        for (var entry : CATEGORY_QUERIES.entrySet()) {
            try {
                results.add(fetchCategory(entry.getKey(), entry.getValue(), lat, lng));
            } catch (Exception e) {
                log.warn("Overpass fetch failed for category {}: {}", entry.getKey(), e.getMessage());
                results.add(new CategoryResult(entry.getKey(), 0, null));
            }
        }
        return results;
    }

    private CategoryResult fetchCategory(String categoryId, String filter, double lat, double lng)
            throws Exception {
        var query = String.format("""
            [out:json][timeout:10];
            (
              %s(around:%d,%f,%f);
            );
            out center qt 20;
            """, filter, RADIUS_M, lat, lng);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(props.getUrl()))
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "ProperiaApi/1.0 (zone-processing)")
            .POST(HttpRequest.BodyPublishers.ofString("data=" + java.net.URLEncoder.encode(query, "UTF-8")))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Overpass returned " + response.statusCode());
        }

        var root     = json.readTree(response.body());
        var elements = root.path("elements");
        int total    = elements.size();
        PoiItem nearest = null;

        double minDist = Double.MAX_VALUE;
        for (JsonNode el : elements) {
            double eLat = el.has("center") ? el.path("center").path("lat").asDouble()
                                           : el.path("lat").asDouble();
            double eLng = el.has("center") ? el.path("center").path("lon").asDouble()
                                           : el.path("lon").asDouble();
            double dist = haversineM(lat, lng, eLat, eLng);
            if (dist < minDist) {
                minDist = dist;
                String name = Optional.ofNullable(el.path("tags").path("name").asText(null))
                    .filter(s -> !s.isBlank())
                    .orElse(null);
                nearest = new PoiItem(name, eLat, eLng, dist);
            }
        }

        return new CategoryResult(categoryId, total, nearest);
    }

    // ── Overpass QL filters per category ──────────────────────────────────────

    private static final Map<String, String> CATEGORY_QUERIES = new LinkedHashMap<>();
    static {
        CATEGORY_QUERIES.put("transportes",
            "node[\"railway\"~\"subway_entrance|tram_stop|station\"];" +
            "node[\"highway\"=\"bus_stop\"];" +
            "node[\"public_transport\"=\"stop_position\"][\"railway\"~\"subway|tram\"]");
        CATEGORY_QUERIES.put("supermercados",
            "node[\"shop\"~\"supermarket|grocery|convenience\"];" +
            "way[\"shop\"~\"supermarket|grocery\"]");
        CATEGORY_QUERIES.put("saude",
            "node[\"amenity\"~\"hospital|clinic|doctors|dentist\"];" +
            "way[\"amenity\"~\"hospital|clinic\"]");
        CATEGORY_QUERIES.put("farmacias",
            "node[\"amenity\"=\"pharmacy\"]");
        CATEGORY_QUERIES.put("escolas",
            "node[\"amenity\"~\"school|kindergarten|university|college\"];" +
            "way[\"amenity\"~\"school|kindergarten|university\"]");
        CATEGORY_QUERIES.put("parques",
            "node[\"leisure\"~\"park|garden|playground\"];" +
            "way[\"leisure\"~\"park|garden\"][\"name\"]");
        CATEGORY_QUERIES.put("cafes_restauracao",
            "node[\"amenity\"~\"cafe|restaurant|bar|fast_food\"]");
        CATEGORY_QUERIES.put("ginasios",
            "node[\"leisure\"~\"fitness_centre|sports_centre|swimming_pool\"];" +
            "way[\"leisure\"~\"fitness_centre|sports_centre\"]");
    }

    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double R     = 6371000;
        double dLat  = Math.toRadians(lat2 - lat1);
        double dLng  = Math.toRadians(lng2 - lng1);
        double a     = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
