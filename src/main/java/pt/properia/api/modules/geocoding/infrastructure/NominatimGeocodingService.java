package pt.properia.api.modules.geocoding.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.geocoding.application.GeocodingResult;
import pt.properia.api.modules.geocoding.application.ListingGeocodingResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NominatimGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingService.class);

    private final GeocodingProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NominatimGeocodingService(GeocodingProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
            .build();
    }

    public Optional<GeocodingResult> geocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        try {
            var arr = nominatimSearch("?q=" + enc(query.strip())
                + "&format=jsonv2&limit=1&countrycodes=pt&addressdetails=1");
            if (arr.isEmpty() || !arr.get(0).isArray() || arr.get(0).isEmpty()) return Optional.empty();
            var first = arr.get(0).get(0);
            return Optional.of(new GeocodingResult(
                first.path("display_name").asText(query),
                first.path("lat").asDouble(),
                first.path("lon").asDouble()
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<ListingGeocodingResult> geocodeListingAddress(
            String street, String streetNumber, String postalCode,
            String city, String parish, String district) {

        // Build combined street string (street + number)
        var streetFull = street != null && !street.isBlank() ? street.strip() : null;
        if (streetFull != null && streetNumber != null && !streetNumber.isBlank()) {
            streetFull = streetFull + " " + streetNumber.strip();
        }

        // 1st: structured with all fields (street + postal + city)
        var candidates = tryStructured(streetFull, postalCode, city, parish);

        // 2nd: structured without postal code — Nominatim often doesn't index PT postal codes
        if (candidates.isEmpty() && streetFull != null) {
            candidates = tryStructured(streetFull, null, city, parish);
        }

        // 3rd: free-text fallback
        if (candidates.isEmpty()) {
            candidates = tryFreeText(streetFull, postalCode, city, parish, district);
        }

        // 4th: postal code + city only (at least finds the neighborhood/city)
        if (candidates.isEmpty() && postalCode != null && !postalCode.isBlank()) {
            candidates = tryStructured(null, postalCode, city, null);
        }

        return candidates;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<ListingGeocodingResult> tryStructured(String street, String postalCode,
                                                         String city, String parish) {
        try {
            var sb = new StringBuilder("?format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (street != null && !street.isBlank())         sb.append("&street=").append(enc(street));
            if (postalCode != null && !postalCode.isBlank()) sb.append("&postalcode=").append(enc(postalCode));
            if (city != null && !city.isBlank())             sb.append("&city=").append(enc(city));
            else if (parish != null && !parish.isBlank())    sb.append("&city=").append(enc(parish));

            var results = nominatimSearch(sb.toString());
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim structured found {} results for street={} pc={} city={}", results.get(0).size(), street, postalCode, city);
            return parseResults(results.get(0));
        } catch (Exception e) {
            log.debug("Nominatim structured error: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ListingGeocodingResult> tryFreeText(String street, String postalCode,
                                                       String city, String parish, String district) {
        try {
            var parts = new ArrayList<String>();
            if (street != null && !street.isBlank())         parts.add(street);
            if (postalCode != null && !postalCode.isBlank()) parts.add(postalCode);
            if (city != null && !city.isBlank())             parts.add(city);
            else if (parish != null && !parish.isBlank())    parts.add(parish);
            if (district != null && !district.isBlank())     parts.add(district);
            parts.add("Portugal");

            var query = String.join(", ", parts);
            var results = nominatimSearch("?q=" + enc(query)
                + "&format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim free-text found {} results for: {}", results.get(0).size(), query);
            return parseResults(results.get(0));
        } catch (Exception e) {
            log.debug("Nominatim free-text error: {}", e.getMessage());
            return List.of();
        }
    }

    // Returns a single-element list containing the parsed array node, or empty on HTTP error
    private List<JsonNode> nominatimSearch(String queryString) throws Exception {
        var url = props.getUrl() + queryString;
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", props.getUserAgent())
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        var arr = objectMapper.readTree(response.body());
        if (!arr.isArray()) return List.of();
        return List.of(arr);
    }

    private List<ListingGeocodingResult> parseResults(JsonNode arr) {
        var results = new ArrayList<ListingGeocodingResult>();
        for (int i = 0; i < Math.min(arr.size(), 5); i++) {
            var item = arr.get(i);
            var r = toListingResult(item);
            if (r != null) results.add(r);
        }
        return results;
    }

    private ListingGeocodingResult toListingResult(JsonNode item) {
        try {
            double lat = item.path("lat").asDouble();
            double lng = item.path("lon").asDouble();
            if (lat == 0 && lng == 0) return null;

            var addr = item.path("address");
            var displayName = item.path("display_name").asText(null);
            var addressType = item.path("addresstype").asText("");
            var type = item.path("type").asText("");

            // Extract address components from Nominatim response
            var road       = coalesce(addr, "road", "pedestrian", "footway", "path");
            var houseNum   = text(addr, "house_number");
            var suburb     = coalesce(addr, "suburb", "neighbourhood", "quarter");
            var cityVal    = coalesce(addr, "city", "town", "municipality", "county");
            var parishVal  = coalesce(addr, "city_district", "borough", "village", "hamlet");
            var districtVal = coalesce(addr, "state", "state_district", "county");
            var postcode   = text(addr, "postcode");

            // Build street with house number if present
            String streetOut = null;
            if (road != null) {
                streetOut = houseNum != null ? road + " " + houseNum : road;
            }

            // Determine precision level
            String precision;
            double confidence;
            if ("house".equals(type) || "house".equals(addressType)) {
                precision = "exact"; confidence = 0.95;
            } else if (road != null && postcode != null) {
                precision = "street"; confidence = 0.80;
            } else if (postcode != null) {
                precision = "neighborhood"; confidence = 0.60;
            } else if (cityVal != null) {
                precision = "parish"; confidence = 0.40;
            } else {
                precision = "municipality"; confidence = 0.20;
            }

            return new ListingGeocodingResult(
                lat, lng,
                precision, confidence,
                displayName,
                districtVal,
                cityVal,
                parishVal,
                suburb,
                streetOut,
                postcode
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode addr, String field) {
        var v = addr.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static String coalesce(JsonNode addr, String... fields) {
        for (var f : fields) {
            var v = text(addr, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
