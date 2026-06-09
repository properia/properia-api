package pt.properia.api.modules.advertiser.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.advertiser.application.GoogleCalendarService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/calendar")
public class AdvertiserCalendarController {

    private static final Logger log = LoggerFactory.getLogger(AdvertiserCalendarController.class);

    private final JdbcClient jdbc;
    private final GoogleCalendarService calendarService;
    private final ObjectMapper json;

    @Value("${properia.google.calendar.client-id:}")
    private String googleClientId;

    @Value("${properia.google.calendar.redirect-uri:http://localhost:8080/api/advertiser/calendar/google/callback}")
    private String redirectUri;

    @Value("${properia.app.url:https://properia.pt}")
    private String appUrl;

    public AdvertiserCalendarController(JdbcClient jdbc,
                                        GoogleCalendarService calendarService,
                                        ObjectMapper json) {
        this.jdbc            = jdbc;
        this.calendarService = calendarService;
        this.json            = json;
    }

    // ── GET /api/advertiser/calendar/google ────────────────────────────────────
    // Returns the active Google Calendar connection for this advertiser,
    // or a "not_connected" summary if none exists.

    @GetMapping("/google")
    public ResponseEntity<?> getConnection(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var advertiserId = requireAdvertiserId(claims);

        var summary = loadConnectionSummary(advertiserId);
        return ResponseEntity.ok(Map.of("data", summary));
    }

    // ── GET /api/advertiser/calendar/google/connect ────────────────────────────
    // Returns the Google OAuth URL as JSON so the frontend can do
    // window.location.href = authUrl — avoids the Next.js rewrite proxy
    // following the 302 server-side and serving Google's HTML with our CSP.

    @GetMapping("/google/connect")
    public ResponseEntity<?> initiateConnect(
            @RequestParam(defaultValue = "/anunciante/visitas") String next,
            @AuthenticationPrincipal JwtClaims claims) {

        requireAuth(claims);
        var advertiserId = requireAdvertiserId(claims);

        if (!calendarService.isConfigured()) {
            return ResponseEntity.ok(Map.of("data", Map.of(
                "configured", false,
                "authUrl", (Object) null
            )));
        }

        try {
            var stateJson = json.writeValueAsString(Map.of(
                "adv",  advertiserId.toString(),
                "uid",  claims.userId().toString(),
                "next", next
            ));
            var state = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stateJson.getBytes(StandardCharsets.UTF_8));

            var authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id="     + enc(googleClientId)
                + "&redirect_uri="  + enc(redirectUri)
                + "&response_type=code"
                + "&scope="         + enc("https://www.googleapis.com/auth/calendar")
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state="         + enc(state);

            return ResponseEntity.ok(Map.of("data", Map.of(
                "configured", true,
                "authUrl", authUrl
            )));
        } catch (Exception e) {
            log.error("Failed to build Google OAuth URL", e);
            return ResponseEntity.ok(Map.of("data", Map.of(
                "configured", false,
                "authUrl", (Object) null
            )));
        }
    }

    // ── GET /api/advertiser/calendar/google/callback ───────────────────────────
    // Google redirects here after the user grants/denies access.
    // Exchanges the authorization code for tokens and upserts them in the DB.

    @GetMapping("/google/callback")
    public ResponseEntity<?> googleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state) {

        var defaultNext = "/anunciante/visitas";

        if (error != null || code == null || state == null) {
            log.warn("Google OAuth callback error or missing params: error={}", error);
            return redirect(defaultNext + "?calendar_error=access_denied");
        }

        String nextUrl = defaultNext;
        try {
            // Decode state → advertiserId, userId, next
            var stateJson = new String(
                Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var stateMap  = (Map<String, String>) json.readValue(stateJson, Map.class);
            var advertiserId = UUID.fromString(stateMap.get("adv"));
            var userId       = UUID.fromString(stateMap.get("uid"));
            nextUrl          = Optional.ofNullable(stateMap.get("next")).orElse(defaultNext);

            // Exchange authorization code for access + refresh tokens
            var tokens = calendarService.exchangeAuthCode(code, redirectUri);

            var encAccess  = calendarService.encrypt(tokens.accessToken());
            var encRefresh = calendarService.encrypt(tokens.refreshToken());
            var expiresAt  = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));

            // Fetch the Google account email for display purposes
            var accountEmail = calendarService.fetchAccountEmail(tokens.accessToken());

            // Upsert: one active connection per advertiser per provider
            jdbc.sql("""
                    INSERT INTO properia.advertiser_calendar_connections
                      (advertiser_id, provider, connected_by_user_id, account_email,
                       access_token_encrypted, refresh_token_encrypted, token_expires_at,
                       scopes, status, created_at, updated_at)
                    VALUES
                      (:adv, 'google_calendar', :uid, :email,
                       :access, :refresh, :expires,
                       '["https://www.googleapis.com/auth/calendar"]'::jsonb,
                       'active', now(), now())
                    ON CONFLICT (advertiser_id, provider) DO UPDATE SET
                      connected_by_user_id    = :uid,
                      account_email           = :email,
                      access_token_encrypted  = :access,
                      refresh_token_encrypted = :refresh,
                      token_expires_at        = :expires,
                      status                  = 'active',
                      updated_at              = now()
                    """)
                .param("adv",     advertiserId)
                .param("uid",     userId)
                .param("email",   accountEmail)
                .param("access",  encAccess)
                .param("refresh", encRefresh)
                .param("expires", expiresAt)
                .update();

            log.info("Google Calendar connected for advertiser {} by user {}", advertiserId, userId);
            return redirect(nextUrl + "?calendar_connected=1");

        } catch (Exception e) {
            log.error("Google Calendar OAuth callback failed", e);
            return redirect(nextUrl + "?calendar_error=callback_failed");
        }
    }

    // ── DELETE /api/advertiser/calendar/google ─────────────────────────────────
    // Disconnects (removes) the Google Calendar connection for this advertiser.

    @DeleteMapping("/google")
    public ResponseEntity<?> disconnect(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var advertiserId = requireAdvertiserId(claims);

        jdbc.sql("""
                DELETE FROM properia.advertiser_calendar_connections
                WHERE advertiser_id = :adv AND provider = 'google_calendar'
                """)
            .param("adv", advertiserId)
            .update();

        // Return "not_connected" summary so the UI can update immediately
        return ResponseEntity.ok(Map.of("data", notConnectedSummary()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> loadConnectionSummary(UUID advertiserId) {
        return jdbc.sql("""
                SELECT account_email, status::text, scopes::text, created_at, updated_at
                FROM properia.advertiser_calendar_connections
                WHERE advertiser_id = :adv AND provider = 'google_calendar'
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var connected = new HashMap<String, Object>();
                connected.put("provider",         "google_calendar");
                connected.put("status",           rs.getString("status"));
                connected.put("accountEmail",     rs.getString("account_email"));
                connected.put("scopes",           parseScopes(rs.getString("scopes")));
                connected.put("connectedAt",      rs.getTimestamp("created_at").toInstant().toString());
                connected.put("updatedAt",        rs.getTimestamp("updated_at").toInstant().toString());
                connected.put("canCreateMeetings", "active".equals(rs.getString("status")));
                connected.put("isConfigured",      calendarService.isConfigured());
                return (Map<String, Object>) connected;
            })
            .optional()
            .orElseGet(this::notConnectedSummary);
    }

    private Map<String, Object> notConnectedSummary() {
        var m = new HashMap<String, Object>();
        m.put("provider",          "google_calendar");
        m.put("status",            "not_connected");
        m.put("accountEmail",      null);
        m.put("scopes",            List.of());
        m.put("connectedAt",       null);
        m.put("updatedAt",         null);
        m.put("canCreateMeetings", false);
        m.put("isConfigured",      calendarService.isConfigured());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseScopes(String scopesJson) {
        if (scopesJson == null) return List.of();
        try {
            return json.readValue(scopesJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ResponseEntity<?> redirect(String url) {
        return ResponseEntity.status(302).header("Location", url).build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
