package pt.properia.api.modules.advertiser.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.advertiser.application.CalendarProviderClient;
import pt.properia.api.modules.advertiser.application.GoogleCalendarService;
import pt.properia.api.modules.advertiser.application.MicrosoftCalendarService;
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
import java.util.Set;
import java.util.UUID;

/**
 * Ligação de calendário PESSOAL de um consultor — Google OU Microsoft (diferente de
 * AdvertiserCalendarController, que liga a agenda partilhada da agência para a sala de Meet,
 * sempre Google). Cada membro da equipa liga a SUA própria agenda, com o provider que já usa
 * no dia a dia; usada para: 1) inserir as visitas atribuídas a ele no telemóvel dele
 * (UserCalendarSyncService), e 2) ler a disponibilidade real dele antes de mostrar slots ao
 * comprador (FreeBusy / getSchedule).
 *
 * Pré-requisito de deploy: os redirect-uris configurados abaixo têm de estar registados nas
 * consolas de cada provider (Google Cloud Console / Azure App Registrations) — são URIs
 * distintos dos usados pela ligação de agência.
 */
@RestController
@RequestMapping("/api/advertiser/team/me/calendar")
public class UserCalendarConnectionController {

    private static final Logger log = LoggerFactory.getLogger(UserCalendarConnectionController.class);
    private static final Set<String> VALID_PROVIDERS = Set.of("google", "microsoft");

    private final JdbcClient jdbc;
    private final GoogleCalendarService googleService;
    private final MicrosoftCalendarService microsoftService;
    private final ObjectMapper json;

    @Value("${properia.google.calendar.client-id:}")
    private String googleClientId;

    @Value("${properia.google.calendar.user-redirect-uri:http://localhost:8080/api/advertiser/team/me/calendar/google/callback}")
    private String googleRedirectUri;

    @Value("${properia.microsoft.calendar.client-id:}")
    private String microsoftClientId;

    @Value("${properia.microsoft.calendar.user-redirect-uri:http://localhost:8080/api/advertiser/team/me/calendar/microsoft/callback}")
    private String microsoftRedirectUri;

    @Value("${properia.app.url:https://properia.pt}")
    private String appUrl;

    public UserCalendarConnectionController(
            JdbcClient jdbc, GoogleCalendarService googleService, MicrosoftCalendarService microsoftService, ObjectMapper json) {
        this.jdbc = jdbc;
        this.googleService = googleService;
        this.microsoftService = microsoftService;
        this.json = json;
    }

    // ── GET /connections — todas as MINHAS ligações (uma entrada por provider já ligado) ──

    @GetMapping("/connections")
    public ResponseEntity<?> listConnections(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var rows = jdbc.sql("""
                SELECT provider, account_email, status, created_at, updated_at
                FROM properia.user_calendar_connections
                WHERE user_id = :uid
                """)
            .param("uid", claims.userId())
            .query((rs, n) -> toSummary(
                rs.getString("provider"), rs.getString("status"), rs.getString("account_email"),
                rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")))
            .list();
        return ResponseEntity.ok(Map.of("data", Map.of("connections", rows)));
    }

    // ── GET /{provider} — estado da MINHA ligação a esse provider ─────────────

    @GetMapping("/{provider}")
    public ResponseEntity<?> getConnection(@PathVariable String provider, @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        requireValidProvider(provider);
        var summary = loadConnectionSummary(claims.userId(), provider);
        return ResponseEntity.ok(Map.of("data", summary));
    }

    // ── GET /{provider}/connect — URL de autorização ───────────────────────────

    @GetMapping("/{provider}/connect")
    public ResponseEntity<?> initiateConnect(
            @PathVariable String provider,
            @RequestParam(defaultValue = "/anunciante/equipa") String next,
            @AuthenticationPrincipal JwtClaims claims) {

        requireAuth(claims);
        requireValidProvider(provider);
        var advertiserId = requireAdvertiserId(claims);
        var client = resolveClient(provider);

        if (!client.isConfigured()) {
            return ResponseEntity.ok(Map.of("data", Map.of("configured", false, "authUrl", (Object) null)));
        }

        try {
            var stateJson = json.writeValueAsString(Map.of(
                "adv", advertiserId.toString(),
                "uid", claims.userId().toString(),
                "next", next
            ));
            var state = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stateJson.getBytes(StandardCharsets.UTF_8));

            var authUrl = "google".equals(provider) ? googleAuthUrl(state, claims.email()) : microsoftAuthUrl(state);
            return ResponseEntity.ok(Map.of("data", Map.of("configured", true, "authUrl", authUrl)));
        } catch (Exception e) {
            log.error("Failed to build {} OAuth URL", provider, e);
            return ResponseEntity.ok(Map.of("data", Map.of("configured", false, "authUrl", (Object) null)));
        }
    }

    private String googleAuthUrl(String state, String userEmail) {
        // Scope de leitura+escrita — precisamos de escrever a visita e de ler FreeBusy.
        //
        // "prompt=consent" sozinho NÃO mostra o seletor de conta quando já há uma sessão
        // Google ativa no browser — o Google avança direto com essa conta, mesmo que seja
        // diferente da que o consultor quer ligar (caso real: consultor logado no Properia
        // como iarussiraphael@gmail.com, mas com raphaeliarussi20@gmail.com já sessionado
        // no browser Google — a ligação ficava silenciosamente presa a essa conta errada,
        // sem o utilizador perceber). "select_account" força sempre o seletor a aparecer;
        // login_hint só pré-seleciona a conta do email de login do Properia (mesmo assim o
        // consultor pode escolher outra no seletor, se usar contas diferentes de propósito).
        return "https://accounts.google.com/o/oauth2/v2/auth"
            + "?client_id="     + enc(googleClientId)
            + "&redirect_uri="  + enc(googleRedirectUri)
            + "&response_type=code"
            + "&scope="         + enc("https://www.googleapis.com/auth/calendar")
            + "&access_type=offline"
            + "&prompt="        + enc("select_account consent")
            + (userEmail != null && !userEmail.isBlank() ? "&login_hint=" + enc(userEmail) : "")
            + "&state="         + enc(state);
    }

    private String microsoftAuthUrl(String state) {
        return MicrosoftCalendarService.authorizeUrl()
            + "?client_id="     + enc(microsoftClientId)
            + "&redirect_uri="  + enc(microsoftRedirectUri)
            + "&response_type=code"
            + "&response_mode=query"
            + "&scope="         + enc(MicrosoftCalendarService.SCOPES)
            + "&state="         + enc(state);
    }

    // ── GET /{provider}/callback — o provider redireciona para aqui ────────────

    @GetMapping("/{provider}/callback")
    public ResponseEntity<?> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state) {

        var defaultNext = "/anunciante/equipa";
        requireValidProvider(provider);

        if (error != null || code == null || state == null) {
            log.warn("{} OAuth callback error or missing params: error={}", provider, error);
            return redirect(appUrl + defaultNext + "?calendar_error=access_denied");
        }

        String nextUrl = defaultNext;
        try {
            var stateJson = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var stateMap = (Map<String, String>) json.readValue(stateJson, Map.class);
            var advertiserId = UUID.fromString(stateMap.get("adv"));
            var userId = UUID.fromString(stateMap.get("uid"));
            nextUrl = Optional.ofNullable(stateMap.get("next")).orElse(defaultNext);

            var client = resolveClient(provider);
            var redirectUri = "google".equals(provider) ? googleRedirectUri : microsoftRedirectUri;

            var tokens = client.exchangeAuthCode(code, redirectUri);
            var encAccess = client.encrypt(tokens.accessToken());
            var encRefresh = client.encrypt(tokens.refreshToken());
            var expiresAt = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
            var accountEmail = client.fetchAccountEmail(tokens.accessToken());
            var scopesJson = "google".equals(provider)
                ? "[\"https://www.googleapis.com/auth/calendar\"]"
                : json.writeValueAsString(List.of(MicrosoftCalendarService.SCOPES.split(" ")));

            jdbc.sql("""
                    INSERT INTO properia.user_calendar_connections
                      (user_id, advertiser_id, provider, account_email,
                       access_token_encrypted, refresh_token_encrypted, token_expires_at,
                       scopes, status, created_at, updated_at)
                    VALUES
                      (:uid, :adv, :provider, :email,
                       :access, :refresh, :expires,
                       :scopes::jsonb, 'active', now(), now())
                    ON CONFLICT (user_id, provider) DO UPDATE SET
                      advertiser_id           = :adv,
                      account_email           = :email,
                      access_token_encrypted  = :access,
                      refresh_token_encrypted = :refresh,
                      token_expires_at        = :expires,
                      status                  = 'active',
                      updated_at              = now()
                    """)
                .param("uid", userId).param("adv", advertiserId).param("provider", provider).param("email", accountEmail)
                .param("access", encAccess).param("refresh", encRefresh).param("expires", expiresAt)
                .param("scopes", scopesJson)
                .update();

            log.info("Personal {} calendar connected for user {}", provider, userId);
            return redirect(appUrl + nextUrl + "?calendar_connected=1&provider=" + provider);
        } catch (Exception e) {
            log.error("{} OAuth callback failed", provider, e);
            return redirect(appUrl + nextUrl + "?calendar_error=callback_failed");
        }
    }

    // ── DELETE /{provider} — desligar ─────────────────────────────────────────

    @DeleteMapping("/{provider}")
    public ResponseEntity<?> disconnect(@PathVariable String provider, @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        requireValidProvider(provider);
        jdbc.sql("DELETE FROM properia.user_calendar_connections WHERE user_id = :uid AND provider = :provider")
            .param("uid", claims.userId()).param("provider", provider)
            .update();
        return ResponseEntity.ok(Map.of("data", notConnectedSummary(provider)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CalendarProviderClient resolveClient(String provider) {
        return "google".equals(provider) ? googleService : microsoftService;
    }

    private void requireValidProvider(String provider) {
        if (!VALID_PROVIDERS.contains(provider)) {
            throw new DomainException("BAD_REQUEST", "Provider de calendário inválido: " + provider, 400);
        }
    }

    private Map<String, Object> loadConnectionSummary(UUID userId, String provider) {
        return jdbc.sql("""
                SELECT account_email, status, created_at, updated_at
                FROM properia.user_calendar_connections
                WHERE user_id = :uid AND provider = :provider
                """)
            .param("uid", userId).param("provider", provider)
            .query((rs, n) -> toSummary(
                provider, rs.getString("status"), rs.getString("account_email"),
                rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")))
            .optional()
            .orElseGet(() -> notConnectedSummary(provider));
    }

    private Map<String, Object> toSummary(String provider, String status, String accountEmail, Timestamp createdAt, Timestamp updatedAt) {
        var m = new HashMap<String, Object>();
        m.put("provider", provider);
        m.put("status", status);
        m.put("accountEmail", accountEmail);
        m.put("connectedAt", createdAt != null ? createdAt.toInstant().toString() : null);
        m.put("updatedAt", updatedAt != null ? updatedAt.toInstant().toString() : null);
        m.put("isConfigured", resolveClient(provider).isConfigured());
        return m;
    }

    private Map<String, Object> notConnectedSummary(String provider) {
        var m = new HashMap<String, Object>();
        m.put("provider", provider);
        m.put("status", "not_connected");
        m.put("accountEmail", null);
        m.put("connectedAt", null);
        m.put("updatedAt", null);
        m.put("isConfigured", resolveClient(provider).isConfigured());
        return m;
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
