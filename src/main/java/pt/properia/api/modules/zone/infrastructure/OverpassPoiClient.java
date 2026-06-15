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

/**
 * Fetches nearby POIs from Overpass API in a SINGLE combined query.
 *
 * Previous design made 8 separate HTTP requests (one per category), which caused
 * systematic rate-limiting on the public Overpass server after the first 2–3 requests.
 * This version builds one union query, fetches all elements in one round-trip, then
 * classifies each element by its OSM tags in Java.
 *
 * Other fixes:
 * - Removed mandatory [name] filter on parques — most parks/playgrounds in Portugal
 *   are mapped without a name tag, causing 0 results.
 * - Added way[] types to farmacias and cafes_restauracao — many shops in Portugal
 *   are mapped as building polygons, not nodes.
 * - Increased Overpass output limit from 25 to 500 (covers dense urban areas).
 */
@Component
public class OverpassPoiClient {

    private static final Logger log = LoggerFactory.getLogger(OverpassPoiClient.class);
    static final int RADIUS_M = 500;
    private static final int OUTPUT_LIMIT = 500;

    private final OverpassProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public OverpassPoiClient(OverpassProperties props, ObjectMapper json) {
        this.props = props;
        this.json  = json;
        this.http  = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(4000))
            .build();
    }

    public record PoiItem(String name, double lat, double lng, double distanceM) {}
    public record CategoryResult(String categoryId, int totalCount, PoiItem nearest) {}

    /**
     * Fetches all zone categories in one Overpass request and classifies by tags.
     * Returns one CategoryResult per category (including those with 0 results).
     */
    public List<CategoryResult> fetchAll(double lat, double lng) {
        var query = buildCombinedQuery(lat, lng);
        try {
            var elements = executeQuery(query, lat, lng);
            return classifyAndAggregate(elements, lat, lng);
        } catch (Exception e) {
            log.warn("Overpass combined fetch failed: {}", e.getMessage());
            // Return empty results for all categories rather than propagating
            return CATEGORY_IDS.stream()
                .map(id -> new CategoryResult(id, 0, null))
                .toList();
        }
    }

    // ── Query construction ────────────────────────────────────────────────────

    private String buildCombinedQuery(double lat, double lng) {
        var around = String.format("(around:%d,%.6f,%.6f)", RADIUS_M, lat, lng);
        var sb = new StringBuilder();
        sb.append(String.format("[out:json][timeout:25];\n(\n"));

        // Transportes
        sb.append("  node[\"railway\"~\"subway_entrance|station|halt|tram_stop\"]").append(around).append(";\n");
        sb.append("  node[\"highway\"=\"bus_stop\"]").append(around).append(";\n");
        sb.append("  node[\"public_transport\"~\"stop_position|platform\"][\"railway\"~\"subway|tram|rail\"]").append(around).append(";\n");
        sb.append("  node[\"amenity\"=\"ferry_terminal\"]").append(around).append(";\n");
        sb.append("  way[\"public_transport\"=\"platform\"]").append(around).append(";\n");

        // Supermercados
        sb.append("  node[\"shop\"~\"supermarket|grocery\"]").append(around).append(";\n");
        sb.append("  way[\"shop\"~\"supermarket|grocery\"]").append(around).append(";\n");
        sb.append("  node[\"shop\"=\"convenience\"][\"brand\"]").append(around).append(";\n");

        // Saúde
        sb.append("  node[\"amenity\"~\"hospital|clinic|doctors|dentist|health_centre\"]").append(around).append(";\n");
        sb.append("  way[\"amenity\"~\"hospital|clinic|health_centre\"]").append(around).append(";\n");

        // Farmácias
        sb.append("  node[\"amenity\"=\"pharmacy\"]").append(around).append(";\n");
        sb.append("  way[\"amenity\"=\"pharmacy\"]").append(around).append(";\n");

        // Escolas
        sb.append("  node[\"amenity\"~\"school|kindergarten|university|college\"]").append(around).append(";\n");
        sb.append("  way[\"amenity\"~\"school|kindergarten|university|college\"]").append(around).append(";\n");

        // Parques — [name] filter removed: most parks in Portugal lack a name tag
        sb.append("  node[\"leisure\"~\"park|garden|playground\"]").append(around).append(";\n");
        sb.append("  way[\"leisure\"~\"park|garden|playground\"]").append(around).append(";\n");
        sb.append("  node[\"landuse\"=\"recreation_ground\"]").append(around).append(";\n");
        sb.append("  way[\"landuse\"=\"recreation_ground\"]").append(around).append(";\n");

        // Cafés & Restauração — added way[] for building-mapped establishments
        sb.append("  node[\"amenity\"~\"cafe|restaurant|bar|fast_food|pub|food_court\"]").append(around).append(";\n");
        sb.append("  way[\"amenity\"~\"cafe|restaurant|bar|fast_food|pub|food_court\"]").append(around).append(";\n");

        // Ginásios
        sb.append("  node[\"leisure\"~\"fitness_centre|sports_centre|sports_hall|swimming_pool\"]").append(around).append(";\n");
        sb.append("  way[\"leisure\"~\"fitness_centre|sports_centre|sports_hall|swimming_pool\"]").append(around).append(";\n");

        sb.append(");\nout center tags qt ").append(OUTPUT_LIMIT).append(";\n");
        return sb.toString();
    }

    // ── HTTP execution ────────────────────────────────────────────────────────

    private List<JsonNode> executeQuery(String query, double lat, double lng) throws Exception {
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
            var body = response.body();
            throw new RuntimeException("Overpass HTTP " + response.statusCode()
                + ": " + body.substring(0, Math.min(300, body.length())));
        }

        var root = json.readTree(response.body());
        var elements = new ArrayList<JsonNode>();
        for (JsonNode el : root.path("elements")) {
            elements.add(el);
        }
        log.debug("Overpass combined query returned {} elements for ({}, {})", elements.size(), lat, lng);
        return elements;
    }

    // ── Classification ────────────────────────────────────────────────────────

    private List<CategoryResult> classifyAndAggregate(List<JsonNode> elements, double lat, double lng) {
        // category → {totalCount, nearest distance, nearest PoiItem}
        var countMap   = new HashMap<String, Integer>();
        var nearestMap = new HashMap<String, PoiItem>();
        var distMap    = new HashMap<String, Double>();

        for (var id : CATEGORY_IDS) {
            countMap.put(id, 0);
            distMap.put(id, Double.MAX_VALUE);
        }

        for (var el : elements) {
            var tags = el.path("tags");
            var categoryId = classify(tags);
            if (categoryId == null) continue;

            double eLat = el.has("center") ? el.path("center").path("lat").asDouble()
                                           : el.path("lat").asDouble();
            double eLng = el.has("center") ? el.path("center").path("lon").asDouble()
                                           : el.path("lon").asDouble();
            if (eLat == 0 && eLng == 0) continue;

            double dist = haversineM(lat, lng, eLat, eLng);
            countMap.merge(categoryId, 1, Integer::sum);

            if (dist < distMap.getOrDefault(categoryId, Double.MAX_VALUE)) {
                distMap.put(categoryId, dist);
                String name = Optional.ofNullable(tags.path("name").asText(null))
                    .filter(s -> !s.isBlank())
                    .orElse(Optional.ofNullable(tags.path("brand").asText(null))
                        .filter(s -> !s.isBlank())
                        .orElse(null));
                nearestMap.put(categoryId, new PoiItem(name, eLat, eLng, dist));
            }
        }

        var results = new ArrayList<CategoryResult>();
        for (var id : CATEGORY_IDS) {
            results.add(new CategoryResult(id, countMap.get(id), nearestMap.get(id)));
            log.debug("Category {}: count={}, nearest={}m", id, countMap.get(id),
                nearestMap.get(id) != null ? (int) nearestMap.get(id).distanceM() : "-");
        }
        return results;
    }

    /**
     * Assigns an OSM element (by its tags) to one of our zone categories.
     * Returns null if the element doesn't match any category.
     * Order matters: more specific checks before broader ones.
     */
    static String classify(JsonNode tags) {
        var amenity         = tags.path("amenity").asText(null);
        var shop            = tags.path("shop").asText(null);
        var highway         = tags.path("highway").asText(null);
        var railway         = tags.path("railway").asText(null);
        var publicTransport = tags.path("public_transport").asText(null);
        var leisure         = tags.path("leisure").asText(null);
        var landuse         = tags.path("landuse").asText(null);
        var brand           = tags.path("brand").asText(null);

        // Transportes
        if ("bus_stop".equals(highway)) return "transportes";
        if (railway != null && Set.of("subway_entrance","station","halt","tram_stop").contains(railway)) return "transportes";
        if (publicTransport != null && Set.of("stop_position","platform").contains(publicTransport)
                && railway != null && Set.of("subway","tram","rail").contains(railway)) return "transportes";
        if ("platform".equals(publicTransport)) return "transportes";
        if ("ferry_terminal".equals(amenity)) return "transportes";

        // Farmácias (before saúde to avoid mis-classification)
        if ("pharmacy".equals(amenity)) return "farmacias";

        // Saúde
        if (amenity != null && Set.of("hospital","clinic","doctors","dentist","health_centre").contains(amenity)) return "saude";

        // Supermercados
        if (shop != null && Set.of("supermarket","grocery").contains(shop)) return "supermercados";
        if ("convenience".equals(shop) && brand != null && !brand.isBlank()) return "supermercados";

        // Escolas
        if (amenity != null && Set.of("school","kindergarten","university","college").contains(amenity)) return "escolas";

        // Parques
        if (leisure != null && Set.of("park","garden","playground").contains(leisure)) return "parques";
        if ("recreation_ground".equals(landuse)) return "parques";

        // Cafés & Restauração
        if (amenity != null && Set.of("cafe","restaurant","bar","fast_food","pub","food_court").contains(amenity)) return "cafes_restauracao";

        // Ginásios
        if (leisure != null && Set.of("fitness_centre","sports_centre","sports_hall","swimming_pool").contains(leisure)) return "ginasios";

        return null;
    }

    // ── Category order (preserved for downstream consumers) ──────────────────

    static final List<String> CATEGORY_IDS = List.of(
        "transportes", "supermercados", "saude", "farmacias",
        "escolas", "parques", "cafes_restauracao", "ginasios"
    );

    // ── Haversine ─────────────────────────────────────────────────────────────

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
