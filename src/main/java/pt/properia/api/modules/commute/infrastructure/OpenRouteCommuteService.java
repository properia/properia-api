package pt.properia.api.modules.commute.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Service
public class OpenRouteCommuteService {

    private final CommuteProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenRouteCommuteService(CommuteProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public record Coordinate(double lat, double lng) {}

    public record MatrixResult(double durationSeconds, double distanceKm) {}

    // ── Directions (polyline para o mapa) ─────────────────────────────────────

    public List<Coordinate> getDirections(
            double originLat, double originLng,
            double destLat, double destLng,
            String mode) {

        var straight = List.of(
            new Coordinate(originLat, originLng),
            new Coordinate(destLat, destLng)
        );

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            return straight;
        }

        try {
            var profile = resolveProfile(mode);
            var url = props.getDirectionsBase() + "/v2/directions/" + profile + "/geojson";

            var bodyMap = Map.of("coordinates", List.of(
                List.of(originLng, originLat),
                List.of(destLng, destLat)
            ));
            var bodyJson = objectMapper.writeValueAsString(bodyMap);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", props.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return straight;

            JsonNode geojson = objectMapper.readTree(response.body());
            JsonNode coords = geojson.path("features").path(0).path("geometry").path("coordinates");

            if (!coords.isArray() || coords.size() < 2) return straight;

            var result = new ArrayList<Coordinate>();
            for (JsonNode coord : coords) {
                result.add(new Coordinate(coord.get(1).asDouble(), coord.get(0).asDouble()));
            }
            return result;

        } catch (Exception e) {
            return straight;
        }
    }

    // ── Matrix (tempos de trajeto em batch) ───────────────────────────────────

    /**
     * Calcula tempos de trajeto de um destino fixo para múltiplas origens.
     * @param destLat   latitude do destino (ex.: local de trabalho)
     * @param destLng   longitude do destino
     * @param origins   lista de [lng, lat] de cada imóvel
     * @param mode      "walking" | "cycling" | "driving" | "transit"
     * @return lista de MatrixResult na mesma ordem que origins; fallback haversine se ORS falhar
     */
    public List<MatrixResult> getMatrix(
            double destLat, double destLng,
            List<double[]> origins,
            String mode) {

        if (origins.isEmpty()) return List.of();

        // Sem API key → estimativa haversine (dev / test env)
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            return haversineFallback(destLat, destLng, origins, mode);
        }

        try {
            return callOrsMatrix(destLat, destLng, origins, mode);
        } catch (Exception e) {
            return haversineFallback(destLat, destLng, origins, mode);
        }
    }

    private List<MatrixResult> callOrsMatrix(
            double destLat, double destLng,
            List<double[]> origins, String mode) throws Exception {

        var profile = resolveProfile(mode);
        var url = props.getDirectionsBase() + "/v2/matrix/" + profile;

        // locations[0] = destino; locations[1..n] = origens
        var locations = new ArrayList<List<Double>>();
        locations.add(List.of(destLng, destLat));
        for (var o : origins) locations.add(List.of(o[0], o[1]));

        var destinations = IntStream.rangeClosed(1, origins.size()).boxed().toList();

        var bodyMap = Map.of(
            "locations",     locations,
            "sources",       List.of(0),
            "destinations",  destinations,
            "metrics",       List.of("duration", "distance")
        );
        var bodyJson = objectMapper.writeValueAsString(bodyMap);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", props.getApiKey())
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return haversineFallback(destLat, destLng, origins, mode);
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode durationRow  = json.path("durations").path(0);  // [d1, d2, ...]
        JsonNode distanceRow  = json.path("distances").path(0);  // [dist1, dist2, ...]

        var results = new ArrayList<MatrixResult>(origins.size());
        for (int i = 0; i < origins.size(); i++) {
            double durSec  = durationRow.path(i).asDouble(-1);
            double distM   = distanceRow.path(i).asDouble(-1);
            if (durSec < 0) {
                // ORS retornou null para este par (sem rota) — fallback individual
                var fb = haversineFallback(destLat, destLng, List.of(origins.get(i)), mode);
                results.add(fb.get(0));
            } else {
                results.add(new MatrixResult(durSec, distM / 1000.0));
            }
        }
        return results;
    }

    // ── Haversine fallback ────────────────────────────────────────────────────

    private List<MatrixResult> haversineFallback(
            double destLat, double destLng,
            List<double[]> origins, String mode) {

        var profile = resolveProfile(mode);
        double speedKmh = speedKmhForProfile(profile);

        return origins.stream().map(o -> {
            double distKm  = haversineKm(o[1], o[0], destLat, destLng);
            // +18 % de desvio para simular percurso real vs. linha recta
            double durSec  = Math.max(240, (distKm / speedKmh) * 3600 * 1.18);
            return new MatrixResult(durSec, distKm);
        }).toList();
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double speedKmhForProfile(String profile) {
        return switch (profile) {
            case "foot-walking"    -> 4.8;
            case "cycling-regular" -> 16.0;
            default                -> 32.0; // driving-car + transit fallback
        };
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    String resolveProfile(String mode) {
        if (mode == null) return "driving-car";
        return switch (mode) {
            case "driving"  -> "driving-car";
            case "walking"  -> "foot-walking";
            case "cycling"  -> "cycling-regular";
            case "transit"  -> "driving-car"; // ORS não suporta transit; usa carro como proxy
            default         -> "driving-car";
        };
    }
}
