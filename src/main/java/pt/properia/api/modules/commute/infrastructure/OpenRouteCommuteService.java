package pt.properia.api.modules.commute.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

            var result = new java.util.ArrayList<Coordinate>();
            for (JsonNode coord : coords) {
                result.add(new Coordinate(coord.get(1).asDouble(), coord.get(0).asDouble()));
            }
            return result;

        } catch (Exception e) {
            return straight;
        }
    }

    private String resolveProfile(String mode) {
        if (mode == null) return "driving-car";
        return switch (mode) {
            case "driving" -> "driving-car";
            case "walking" -> "foot-walking";
            case "cycling" -> "cycling-regular";
            default -> "driving-car";
        };
    }
}
