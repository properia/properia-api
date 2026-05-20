package pt.properia.api.modules.geocoding.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.geocoding.application.GeocodingResult;
import pt.properia.api.shared.domain.DomainException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Service
public class NominatimGeocodingService {

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
            var encoded = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
            var url = props.getUrl()
                + "?q=" + encoded
                + "&format=jsonv2&limit=1&countrycodes=pt&addressdetails=1";

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .GET()
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return Optional.empty();

            JsonNode arr = objectMapper.readTree(response.body());
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();

            JsonNode first = arr.get(0);
            double lat = first.path("lat").asDouble();
            double lng = first.path("lon").asDouble();
            String label = first.path("display_name").asText(query);

            return Optional.of(new GeocodingResult(label, lat, lng));

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<GeocodingResult> geocodeListingAddress(
            String street, String streetNumber, String postalCode,
            String city, String parish, String district) {
        var parts = new java.util.StringJoiner(", ");
        if (street != null && !street.isBlank()) {
            var streetPart = street.strip();
            if (streetNumber != null && !streetNumber.isBlank()) streetPart += " " + streetNumber.strip();
            parts.add(streetPart);
        }
        if (postalCode != null && !postalCode.isBlank()) parts.add(postalCode.strip());
        if (city != null && !city.isBlank()) parts.add(city.strip());
        else if (parish != null && !parish.isBlank()) parts.add(parish.strip());
        if (district != null && !district.isBlank()) parts.add(district.strip());
        parts.add("Portugal");

        return geocode(parts.toString());
    }
}
