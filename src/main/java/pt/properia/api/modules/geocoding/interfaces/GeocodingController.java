package pt.properia.api.modules.geocoding.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.geocoding.application.ListingGeocodingResult;
import pt.properia.api.modules.geocoding.infrastructure.NominatimGeocodingService;
import pt.properia.api.shared.domain.DomainException;

import java.util.LinkedHashMap;
import java.util.List;
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

    @GetMapping("/destination/suggestions")
    public ResponseEntity<?> geocodeSuggestions(@RequestParam(value = "q", required = false) String q) {
        if (q == null || q.strip().length() < 2) {
            return ResponseEntity.ok(Map.of("data", Map.of("items", List.of())));
        }

        var items = geocodingService.suggest(q.strip(), 5).stream()
            .map(r -> Map.of(
                "label", r.label(),
                "lat", r.lat(),
                "lng", r.lng()
            ))
            .toList();

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @PostMapping("/listing-location")
    public ResponseEntity<?> geocodeListingLocation(@RequestBody Map<String, String> body) {
        var candidates = geocodingService.geocodeListingAddress(
            body.get("street"), body.get("streetNumber"),
            body.get("postalCode"), body.get("city"),
            body.get("parish"), body.get("district")
        );

        if (candidates.isEmpty()) {
            throw new DomainException("NOT_FOUND", "Não foi possível geocodificar este endereço.", 404);
        }

        var best = candidates.get(0);
        var candidateList = candidates.stream().map(this::toMap).toList();
        var data = toMap(best);
        data.put("candidates", candidateList);

        return ResponseEntity.ok(Map.of("data", data));
    }

    private LinkedHashMap<String, Object> toMap(ListingGeocodingResult c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("latitude",       c.latitude());
        m.put("longitude",      c.longitude());
        m.put("precision",      c.precision());
        m.put("confidence",     c.confidence());
        m.put("displayAddress", c.displayAddress());
        m.put("district",       c.district());
        m.put("city",           c.city());
        m.put("parish",         c.parish());
        m.put("neighborhood",   c.neighborhood());
        m.put("street",         c.street());
        m.put("postalCode",     c.postalCode());
        return m;
    }
}
