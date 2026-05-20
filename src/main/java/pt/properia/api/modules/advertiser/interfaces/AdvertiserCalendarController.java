package pt.properia.api.modules.advertiser.interfaces;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/calendar")
public class AdvertiserCalendarController {

    private final JdbcClient jdbc;

    @Value("${properia.google.calendar.client-id:}")
    private String googleClientId;

    public AdvertiserCalendarController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/google")
    public ResponseEntity<?> getGoogleCalendarConnections(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var advertiserId = requireAdvertiserId(claims);

        var connections = jdbc.sql("""
                SELECT id, provider, email, status, created_at
                FROM properia.calendar_connections
                WHERE advertiser_id = :adv AND user_id = :uid
                ORDER BY created_at DESC
                """)
            .param("adv", advertiserId)
            .param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "provider", rs.getString("provider"),
                "email", Optional.ofNullable(rs.getString("email")).orElse(""),
                "status", rs.getString("status"),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString()
            ))
            .list();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "connections", connections,
            "configured", !googleClientId.isBlank()
        )));
    }

    @GetMapping("/google/connect")
    public ResponseEntity<?> initiateGoogleConnect(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        if (googleClientId.isBlank()) {
            return ResponseEntity.ok(Map.of("data", Map.of(
                "configured", false,
                "message", "Google Calendar integration not configured."
            )));
        }
        // Return auth URL for client to redirect
        var redirectUri = "/api/advertiser/calendar/google/callback";
        var authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
            + "?client_id=" + googleClientId
            + "&redirect_uri=" + redirectUri
            + "&response_type=code"
            + "&scope=https://www.googleapis.com/auth/calendar"
            + "&access_type=offline&prompt=consent";
        return ResponseEntity.ok(Map.of("data", Map.of("authUrl", authUrl, "configured", true)));
    }

    @PostMapping("/google/connect")
    public ResponseEntity<?> connectGoogle(@RequestBody Map<String, String> body,
                                           @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        if (googleClientId.isBlank()) {
            throw new DomainException("NOT_IMPLEMENTED", "Google Calendar integration not configured.", 501);
        }
        // In production: exchange code for tokens and store in calendar_connections
        return ResponseEntity.ok(Map.of("data", Map.of("connected", false, "message", "OAuth flow not fully implemented.")));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<?> googleCallback(@RequestParam(required = false) String code,
                                            @RequestParam(required = false) String error) {
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "OAUTH_ERROR", "message", error)));
        }
        // In production: exchange code, store tokens, redirect to UI
        return ResponseEntity.ok(Map.of("data", Map.of("connected", true)));
    }

    @DeleteMapping("/google/{connectionId}")
    public ResponseEntity<?> disconnectGoogle(@PathVariable UUID connectionId,
                                              @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var advertiserId = requireAdvertiserId(claims);
        jdbc.sql("""
                DELETE FROM properia.calendar_connections
                WHERE id = :id AND advertiser_id = :adv AND user_id = :uid
                """).param("id", connectionId).param("adv", advertiserId).param("uid", claims.userId()).update();
        return ResponseEntity.ok(Map.of("data", Map.of("disconnected", true)));
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
