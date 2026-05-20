package pt.properia.api.modules.commute.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.commute.infrastructure.OpenRouteCommuteService;
import pt.properia.api.shared.domain.DomainException;

import java.util.Map;

@RestController
@RequestMapping("/api/commute")
public class CommuteController {

    private final OpenRouteCommuteService commuteService;

    public CommuteController(OpenRouteCommuteService commuteService) {
        this.commuteService = commuteService;
    }

    @GetMapping("/directions")
    public ResponseEntity<?> directions(
            @RequestParam double originLat,
            @RequestParam double originLng,
            @RequestParam double destLat,
            @RequestParam double destLng,
            @RequestParam(defaultValue = "driving") String mode) {

        if (!isValidCoord(originLat, -90, 90) || !isValidCoord(originLng, -180, 180)
                || !isValidCoord(destLat, -90, 90) || !isValidCoord(destLng, -180, 180)) {
            throw new DomainException("VALIDATION_ERROR", "Parâmetros de coordenadas inválidos.", 400);
        }

        var polyline = commuteService.getDirections(originLat, originLng, destLat, destLng, mode);
        var points = polyline.stream()
            .map(c -> Map.of("lat", c.lat(), "lng", c.lng()))
            .toList();

        return ResponseEntity.ok(Map.of("data", Map.of("polyline", points)));
    }

    private boolean isValidCoord(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }
}
