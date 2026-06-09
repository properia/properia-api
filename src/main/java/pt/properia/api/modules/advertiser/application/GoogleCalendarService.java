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
import java.util.Base64;
import java.util.Map;

/**
 * Calls the Google Calendar API on behalf of a connected advertiser.
 *
 * Handles:
 * - Creating calendar events with a Google Meet conference link (conferenceData)
 * - Refreshing expired access tokens using the stored refresh token
 * - AES-256-GCM encryption/decryption of tokens at rest
 *
 * Requires env vars: GOOGLE_CALENDAR_CLIENT_ID, GOOGLE_CALENDAR_CLIENT_SECRET,
 * GOOGLE_CALENDAR_TOKEN_KEY (32-byte base64 key for AES-256).
 */
@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String CALENDAR_API   = "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    @Value("${properia.google.calendar.client-id:}")
    private String clientId;

    @Value("${properia.google.calendar.client-secret:}")
    private String clientSecret;

    @Value("${properia.google.calendar.token-encryption-key:}")
    private String encryptionKeyB64;

    private final ObjectMapper json;
    private final HttpClient   http;

    public GoogleCalendarService(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public record MeetResult(String meetUrl, String calendarEventId) {}

    /**
     * Creates a Google Calendar event with a Meet conference link and returns the hangout URL.
     *
     * @param accessToken  valid (or recently refreshed) Google access token
     * @param visitId      used as idempotency key for the conference request
     * @param summary      event title shown in Google Calendar
     * @param startIso     ISO-8601 start time (e.g. "2026-06-10T10:00:00")
     * @param endIso       ISO-8601 end time
     * @param timezone     IANA timezone (e.g. "Europe/Lisbon")
     * @param buyerEmail   invited attendee — may be null
     */
    public MeetResult createMeetEvent(
            String accessToken, String visitId, String summary,
            String startIso, String endIso, String timezone,
            String buyerEmail) throws Exception {

        var attendees = buyerEmail != null
            ? json.writeValueAsString(new Object[]{Map.of("email", buyerEmail)})
            : "[]";

        var body = """
            {
              "summary": %s,
              "start":   {"dateTime": %s, "timeZone": %s},
              "end":     {"dateTime": %s, "timeZone": %s},
              "attendees": %s,
              "conferenceData": {
                "createRequest": {"requestId": %s}
              }
            }
            """.formatted(
                json.writeValueAsString(summary),
                json.writeValueAsString(startIso),
                json.writeValueAsString(timezone),
                json.writeValueAsString(endIso),
                json.writeValueAsString(timezone),
                attendees,
                json.writeValueAsString("properia-" + visitId)
            );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(CALENDAR_API + "?conferenceDataVersion=1"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Google Calendar API error " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var eventId     = (String) parsed.get("id");

        @SuppressWarnings("unchecked")
        var confData = (Map<String, Object>) parsed.get("conferenceData");
        var meetUrl  = confData != null ? (String) confData.get("hangoutLink") : null;

        if (meetUrl == null) {
            throw new RuntimeException("Google Calendar did not return a Meet link for visitId=" + visitId);
        }

        log.info("Google Meet created for visit {}: {}", visitId, meetUrl);
        return new MeetResult(meetUrl, eventId);
    }

    public record TokenResult(String accessToken, String refreshToken, long expiresIn) {}

    /**
     * Exchanges an OAuth authorization code (from the callback) for access + refresh tokens.
     */
    @SuppressWarnings("unchecked")
    public TokenResult exchangeAuthCode(String code, String redirectUri) throws Exception {
        var body = "grant_type=authorization_code"
            + "&code="          + java.net.URLEncoder.encode(code, StandardCharsets.UTF_8)
            + "&redirect_uri="  + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
            + "&client_id="     + java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Auth code exchange failed: " + response.statusCode() + " " + response.body());
        }

        var parsed       = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var accessToken  = (String) parsed.get("access_token");
        var refreshToken = (String) parsed.get("refresh_token");
        var expiresIn    = parsed.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
        if (accessToken == null) throw new RuntimeException("access_token absent in auth code response");
        return new TokenResult(accessToken, refreshToken, expiresIn);
    }

    /**
     * Fetches the Google account email associated with an access token.
     * Returns null if the call fails rather than throwing.
     */
    @SuppressWarnings("unchecked")
    public String fetchAccountEmail(String accessToken) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
                return (String) parsed.get("email");
            }
        } catch (Exception e) {
            log.warn("Could not fetch Google account email: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Exchanges a refresh token for a new access token.
     * Returns the new access token, or throws if the refresh token is invalid/revoked.
     */
    @SuppressWarnings("unchecked")
    public String refreshAccessToken(String refreshToken) throws Exception {
        var body = "grant_type=refresh_token"
            + "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
            + "&client_id="     + java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_ENDPOINT))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Token refresh failed: " + response.statusCode() + " " + response.body());
        }

        var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
        var token  = (String) parsed.get("access_token");
        if (token == null) throw new RuntimeException("access_token absent in refresh response");
        return token;
    }

    // ── AES-256-GCM encryption ────────────────────────────────────────────────

    /**
     * Encrypts a plaintext string using AES-256-GCM.
     * Returns a base64-encoded string of format: nonce(12B) || ciphertext.
     * Returns null if the encryption key is not configured (tokens stored as-is in dev).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (encryptionKeyB64 == null || encryptionKeyB64.isBlank()) return plaintext;
        try {
            var key    = buildKey();
            var nonce  = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            var ct     = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var out    = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    /**
     * Decrypts a value previously produced by {@link #encrypt}.
     * Returns null if the encryption key is not configured.
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        if (encryptionKeyB64 == null || encryptionKeyB64.isBlank()) return ciphertext;
        try {
            var raw    = Base64.getDecoder().decode(ciphertext);
            var nonce  = new byte[12];
            System.arraycopy(raw, 0, nonce, 0, nonce.length);
            var ct     = new byte[raw.length - 12];
            System.arraycopy(raw, 12, ct, 0, ct.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token decryption failed", e);
        }
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }

    private SecretKey buildKey() {
        var raw = Base64.getDecoder().decode(encryptionKeyB64);
        if (raw.length != 32) throw new IllegalStateException("GOOGLE_CALENDAR_TOKEN_KEY must be 32 bytes base64");
        return new SecretKeySpec(raw, "AES");
    }
}
