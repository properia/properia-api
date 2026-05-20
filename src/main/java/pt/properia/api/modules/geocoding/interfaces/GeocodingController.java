package pt.properia.api.modules.geocoding.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.geocoding.infrastructure.NominatimGeocodingService;
import pt.properia.api.shared.domain.DomainException;

import java.util.Map;

@RestController
@RequestMapping("/api/geocoding")
public class GeocodingController {

    private final NominatimGeocodingService geocodingService;

    public GeocodingController(NominatimGeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @PostMapping("/destination")
    public ResponseEntity<?> geocodeDestination(@RequestBody Map<String, String> body) {
        var query = body.get("q");
        if (query == null || query.strip().length() < 3) {
            throw new DomainException("VALIDATION_ERROR", "Indica um destino com pelo menos 3 caracteres.", 400);
        }

        var result = geocodingService.geocode(query.strip())
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Não encontrámos esse destino.", 404));

        return ResponseEntity.ok(Map.of("data", Map.of(
            "label", result.label(),
            "lat", result.lat(),
            "lng", result.lng()
        )));
    }

    @PostMapping("/destination/suggestions")
    public ResponseEntity<?> geocodeSuggestions(@RequestBody Map<String, String> body) {
        var query = body.get("q");
        if (query == null || query.strip().length() < 2) {
            return ResponseEntity.ok(Map.of("data", Map.of("items", java.util.List.of())));
        }

        var result = geocodingService.geocode(query.strip());
        var items = result.map(r -> java.util.List.of(Map.of(
            "label", r.label(),
            "lat", r.lat(),
            "lng", r.lng()
        ))).orElse(java.util.List.of());

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping("/listing-location")
    public ResponseEntity<?> geocodeListingLocation(@RequestBody Map<String, String> body) {
        var result = geocodingService.geocodeListingAddress(
            body.get("street"), body.get("streetNumber"),
            body.get("postalCode"), body.get("city"),
            body.get("parish"), body.get("district")
        );

        if (result.isEmpty()) {
            throw new DomainException("NOT_FOUND", "Não foi possível geocodificar este endereço.", 404);
        }

        var r = result.get();
        return ResponseEntity.ok(Map.of("data", Map.of(
            "lat", r.lat(),
            "lng", r.lng(),
            "label", r.label()
        )));
    }
}
