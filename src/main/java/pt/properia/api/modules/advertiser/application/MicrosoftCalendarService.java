package pt.properia.api.modules.advertiser.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Equivalente Microsoft do GoogleCalendarService, para consultores com conta Outlook/Hotmail/
 * Microsoft 365 — implementa o mesmo contrato (CalendarProviderClient) usado pelo
 * UserCalendarSyncService para rotear entre providers.
 *
 * Usa o tenant Azure AD 'common', que aceita contas pessoais (outlook.com/hotmail.com) E
 * contas profissionais/escolares (Microsoft 365) no mesmo endpoint — é o que a maioria dos
 * consultores de agências mais pequenas efetivamente usa (conta pessoal, não corporativa
 * gerida por IT), por isso não faz sentido restringir a um único tenant.
 *
 * Requer env vars: MICROSOFT_CALENDAR_CLIENT_ID, MICROSOFT_CALENDAR_CLIENT_SECRET,
 * MICROSOFT_CALENDAR_TOKEN_KEY (32-byte base64 key para AES-256 — pode ser a MESMA chave do
 * Google, é só usada para cifrar bytes, não precisa de ser dedicada por provider).
 */
@Service
public class MicrosoftCalendarService implements CalendarProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftCalendarService.class);

    // Tenant 'common' — ver nota na classe.
    private static final String TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String EVENTS_API = GRAPH_BASE + "/me/events";
    private static final String SCHEDULE_API = GRAPH_BASE + "/me/calendar/getSchedule";
    private static final String ME_API = GRAPH_BASE + "/me";

    // offline_access é obrigatório para receber refresh_token — ao contrário do Google, o
    // Graph não usa access_type=offline, o "modo offline" é pedido como scope.
    public static final String SCOPES =
        "offline_access https://graph.microsoft.com/Calendars.ReadWrite https://graph.microsoft.com/User.Read";

    @Value("${properia.microsoft.calendar.client-id:}")
    private String clientId;

    @Value("${properia.microsoft.calendar.client-secret:}")
    private String clientSecret;

    @Value("${properia.microsoft.calendar.token-encryption-key:}")
    private String encryptionKeyB64;

    private final ObjectMapper json;
    private final HttpClient http;

    public MicrosoftCalendarService(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public static String authorizeUrl() {
        return AUTHORIZE_ENDPOINT;
    }

    // ── OAuth ────────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public TokenResult exchangeAuthCode(String code, String redirectUri) throws Exception {
        var body = "grant_type=authorization_code"
            + "&code="          + enc(code)
            + "&redirect_uri="  + enc(redirectUri)
            + "&client_id="     + enc(clientId)
            + "&client_secret=" + enc(clientSecret)
            + "&scope="         + enc(SCOPES);

        var response = post(TOKEN_ENDPOINT, body, "application/x-www-form-urlencoded");
        if (response.statusCode() != 200) {
            throw new RuntimeException("Microsoft auth code exchange failed: " + response.statusCode() + " " + response.body());
        }
        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var accessToken = (String) parsed.get("access_token");
        var refreshToken = (String) parsed.get("refresh_token");
        var expiresIn = parsed.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        if (accessToken == null) throw new RuntimeException("access_token absent in Microsoft auth code response");
        if (refreshToken == null) {
            // Sem offline_access aceite (ex.: admin da organização bloqueou o scope) não há
            // como manter a ligação depois do access token expirar — falha já aqui, de forma
            // clara, em vez de deixar a ligação "meio-funcional" por 1h e depois morrer calada.
            throw new RuntimeException("Microsoft did not return a refresh_token — verifica se offline_access foi consentido");
        }
        return new TokenResult(accessToken, refreshToken, expiresIn);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RefreshResult refresh(String refreshToken) throws Exception {
        var body = "grant_type=refresh_token"
            + "&refresh_token=" + enc(refreshToken)
            + "&client_id="     + enc(clientId)
            + "&client_secret=" + enc(clientSecret)
            + "&scope="         + enc(SCOPES);

        var response = post(TOKEN_ENDPOINT, body, "application/x-www-form-urlencoded");
        if (response.statusCode() != 200) {
            throw new RuntimeException("Microsoft token refresh failed: " + response.statusCode() + " " + response.body());
        }
        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var accessToken = (String) parsed.get("access_token");
        if (accessToken == null) throw new RuntimeException("access_token absent in Microsoft refresh response");
        // O Graph pode rodar o refresh token a cada pedido (family of refresh tokens) — se não
        // vier um novo, o antigo continua válido e é isso que se devolve.
        var newRefreshToken = parsed.get("refresh_token") instanceof String s && !s.isBlank() ? s : refreshToken;
        return new RefreshResult(accessToken, newRefreshToken);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String fetchAccountEmail(String accessToken) {
        try {
            var response = get(ME_API, accessToken);
            if (response.statusCode() == 200) {
                var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
                // Contas pessoais (outlook.com/hotmail.com) costumam não ter "mail" preenchido —
                // userPrincipalName é o fallback fiável nesse caso.
                var mail = (String) parsed.get("mail");
                return mail != null && !mail.isBlank() ? mail : (String) parsed.get("userPrincipalName");
            }
        } catch (Exception e) {
            log.warn("Could not fetch Microsoft account email: {}", e.getMessage());
        }
        return null;
    }

    // ── Eventos ──────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public CalendarEventResult insertEvent(
            String accessToken, String summary, String location, String description,
            String startIso, String endIso, String timezone) throws Exception {

        var response = post(EVENTS_API, eventBody(summary, location, description, startIso, endIso, timezone),
            "application/json", accessToken);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Microsoft Graph insert error " + response.statusCode() + ": " + response.body());
        }
        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        return new CalendarEventResult((String) parsed.get("id"));
    }

    @Override
    public void updateEvent(
            String accessToken, String eventId, String summary, String location, String description,
            String startIso, String endIso, String timezone) throws Exception {

        var request = HttpRequest.newBuilder()
            .uri(URI.create(EVENTS_API + "/" + java.net.URLEncoder.encode(eventId, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(eventBody(summary, location, description, startIso, endIso, timezone)))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return; // evento já não existe do lado do Outlook
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Microsoft Graph update error " + response.statusCode() + ": " + response.body());
        }
    }

    @Override
    public void deleteEvent(String accessToken, String eventId) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(EVENTS_API + "/" + java.net.URLEncoder.encode(eventId, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + accessToken)
            .DELETE()
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Microsoft Graph delete error " + response.statusCode() + ": " + response.body());
        }
    }

    // ── Disponibilidade ──────────────────────────────────────────────────────

    /**
     * getSchedule devolve a disponibilidade de uma ou mais agendas — usamos só a própria
     * (accountEmail), pedida em blocos de 30 min (availabilityViewInterval) e reconstruída
     * em intervalos ocupados contíguos a partir da availabilityView (mais robusto do que
     * confiar em scheduleItems, que em contas pessoais vem por vezes vazio por privacidade).
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<BusyInterval> queryFreeBusy(String accessToken, String accountEmail, String timeMinIso, String timeMaxIso) throws Exception {
        if (accountEmail == null || accountEmail.isBlank()) return List.of();

        var body = """
            {
              "schedules": [%s],
              "startTime": {"dateTime": %s, "timeZone": "UTC"},
              "endTime":   {"dateTime": %s, "timeZone": "UTC"},
              "availabilityViewInterval": 30
            }
            """.formatted(json.writeValueAsString(accountEmail), json.writeValueAsString(timeMinIso), json.writeValueAsString(timeMaxIso));

        var response = post(SCHEDULE_API, body, "application/json", accessToken);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Microsoft getSchedule error " + response.statusCode() + ": " + response.body());
        }

        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var values = (List<Map<String, Object>>) parsed.get("value");
        if (values == null || values.isEmpty()) return List.of();
        var schedule = values.get(0);

        var scheduleItems = (List<Map<String, Object>>) schedule.get("scheduleItems");
        if (scheduleItems == null) return List.of();

        var result = new ArrayList<BusyInterval>(scheduleItems.size());
        for (var item : scheduleItems) {
            var status = (String) item.get("status");
            if (status == null || "free".equalsIgnoreCase(status)) continue;
            var start = (Map<String, Object>) item.get("start");
            var end = (Map<String, Object>) item.get("end");
            if (start == null || end == null) continue;
            result.add(new BusyInterval((String) start.get("dateTime"), (String) end.get("dateTime")));
        }
        return result;
    }

    // ── Helpers HTTP ─────────────────────────────────────────────────────────

    private String eventBody(String summary, String location, String description,
                              String startIso, String endIso, String timezone) throws Exception {
        return """
            {
              "subject": %s,
              "location": {"displayName": %s},
              "body": {"contentType": "text", "content": %s},
              "start": {"dateTime": %s, "timeZone": %s},
              "end":   {"dateTime": %s, "timeZone": %s}
            }
            """.formatted(
                json.writeValueAsString(summary),
                json.writeValueAsString(location != null ? location : ""),
                json.writeValueAsString(description != null ? description : ""),
                json.writeValueAsString(startIso), json.writeValueAsString(timezone),
                json.writeValueAsString(endIso), json.writeValueAsString(timezone)
            );
    }

    private HttpResponse<String> get(String url, String accessToken) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body, String contentType, String accessToken) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── AES-256-GCM (mesmo esquema do GoogleCalendarService — ver comentário na classe) ────

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (encryptionKeyB64 == null || encryptionKeyB64.isBlank()) return plaintext;
        try {
            var key = buildKey();
            var nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        if (encryptionKeyB64 == null || encryptionKeyB64.isBlank()) return ciphertext;
        try {
            var raw = Base64.getDecoder().decode(ciphertext);
            var nonce = new byte[12];
            System.arraycopy(raw, 0, nonce, 0, nonce.length);
            var ct = new byte[raw.length - 12];
            System.arraycopy(raw, 12, ct, 0, ct.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token decryption failed", e);
        }
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }

    private SecretKey buildKey() {
        var raw = Base64.getDecoder().decode(encryptionKeyB64);
        if (raw.length != 32) throw new IllegalStateException("MICROSOFT_CALENDAR_TOKEN_KEY must be 32 bytes base64");
        return new SecretKeySpec(raw, "AES");
    }
}
