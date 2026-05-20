package pt.properia.api.modules.locations.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.locations.infrastructure.JdbcLocationRepository;

import java.util.Map;

@RestController
@RequestMapping("/api/locations")
public class LocationsController {

    private final JdbcLocationRepository repository;

    public LocationsController(JdbcLocationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "suggestions") String emptyStateMode) {

        boolean emptyState = !"none".equals(emptyStateMode);
        var items = repository.suggest(q, emptyState);
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }
}
