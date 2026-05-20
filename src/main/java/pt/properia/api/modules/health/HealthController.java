package pt.properia.api.modules.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Simple health check endpoint.
 * Spring Actuator provides /actuator/health with deep checks (DB, etc.).
 * This /api/health endpoint is the lightweight equivalent for load balancers and Nginx.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Platform health and readiness")
public class HealthController {

    private static final String VERSION = "0.1.0";

    @GetMapping("/health")
    @Operation(summary = "API health check", description = "Returns 200 if the API is up.")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("ok", VERSION, Instant.now()));
    }

    public record HealthResponse(
        String status,
        String version,
        Instant timestamp
    ) {}
}
