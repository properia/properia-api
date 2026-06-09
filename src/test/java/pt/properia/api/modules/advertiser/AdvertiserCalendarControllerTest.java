package pt.properia.api.modules.advertiser;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Google Calendar connection endpoints.
 *
 * Tests cover:
 * - GET /api/advertiser/calendar/google returns "not_connected" when no row
 * - GET /api/advertiser/calendar/google returns connection details when a row exists
 * - GET /api/advertiser/calendar/google/connect redirects (302) to Google OAuth
 * - DELETE /api/advertiser/calendar/google removes the connection
 * - Unauthenticated requests are rejected with 401
 *
 * OAuth token exchange (GET /callback) is not tested here — it requires
 * a live or mocked Google token endpoint. The logic is covered by unit tests
 * for GoogleCalendarService.exchangeAuthCode().
 */
class AdvertiserCalendarControllerTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID userId;
    private String agentToken;

    @BeforeEach
    void setUpAdvertiser() {
        advertiserId = UUID.randomUUID();
        userId       = UUID.randomUUID();

        // Insert minimal app_user
        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, name, role, created_at, updated_at)
                VALUES (:id, :email, 'Test Agent', 'agent', now(), now())
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id",    userId)
            .param("email", userId + "@test.properia.pt")
            .update();

        // Insert minimal advertiser
        jdbc.sql("""
                INSERT INTO properia.advertisers
                  (id, name, brand_name, email, status, plan_code, created_at, updated_at)
                VALUES (:id, 'Test Advertiser', 'Test Brand', :email, 'active', 'starter', now(), now())
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id",    advertiserId)
            .param("email", advertiserId + "@advertiser.test")
            .update();

        agentToken = generateToken(userId, "agent", true, advertiserId);

        RestAssured.port = port;
    }

    // ── GET /google: not connected ─────────────────────────────────────────────

    @Test
    void getConnection_returnsNotConnectedWhenNoRow() {
        given()
            .cookie("properia_session", agentToken)
        .when()
            .get("/api/advertiser/calendar/google")
        .then()
            .statusCode(200)
            .body("data.status",            equalTo("not_connected"))
            .body("data.provider",          equalTo("google_calendar"))
            .body("data.accountEmail",      nullValue())
            .body("data.canCreateMeetings", equalTo(false));
    }

    // ── GET /google: active connection ────────────────────────────────────────

    @Test
    void getConnection_returnsActiveConnectionDetails() {
        // Insert an active connection directly
        jdbc.sql("""
                INSERT INTO properia.advertiser_calendar_connections
                  (id, advertiser_id, provider, connected_by_user_id, account_email,
                   access_token_encrypted, refresh_token_encrypted,
                   token_expires_at, scopes, status, created_at, updated_at)
                VALUES
                  (gen_random_uuid(), :adv, 'google_calendar', :uid, 'agent@gmail.com',
                   'enc_access', 'enc_refresh',
                   now() + interval '1 hour',
                   '["https://www.googleapis.com/auth/calendar"]'::jsonb,
                   'active', now(), now())
                ON CONFLICT (advertiser_id, provider) DO NOTHING
                """)
            .param("adv", advertiserId)
            .param("uid", userId)
            .update();

        given()
            .cookie("properia_session", agentToken)
        .when()
            .get("/api/advertiser/calendar/google")
        .then()
            .statusCode(200)
            .body("data.status",            equalTo("active"))
            .body("data.provider",          equalTo("google_calendar"))
            .body("data.accountEmail",      equalTo("agent@gmail.com"))
            .body("data.canCreateMeetings", equalTo(true))
            .body("data.connectedAt",       notNullValue())
            .body("data.scopes",            hasItem("https://www.googleapis.com/auth/calendar"));
    }

    // ── DELETE /google: disconnects ───────────────────────────────────────────

    @Test
    void disconnect_removesConnectionAndReturnsNotConnected() {
        // Insert an active connection
        jdbc.sql("""
                INSERT INTO properia.advertiser_calendar_connections
                  (id, advertiser_id, provider, connected_by_user_id, account_email,
                   access_token_encrypted, refresh_token_encrypted,
                   token_expires_at, scopes, status, created_at, updated_at)
                VALUES
                  (gen_random_uuid(), :adv, 'google_calendar', :uid, 'delete@gmail.com',
                   'enc', 'enc', now() + interval '1 hour',
                   '[]'::jsonb, 'active', now(), now())
                ON CONFLICT (advertiser_id, provider) DO UPDATE SET status = 'active'
                """)
            .param("adv", advertiserId)
            .param("uid", userId)
            .update();

        // Disconnect
        given()
            .cookie("properia_session", agentToken)
        .when()
            .delete("/api/advertiser/calendar/google")
        .then()
            .statusCode(200)
            .body("data.status", equalTo("not_connected"));

        // Verify row is gone from the DB
        var count = jdbc.sql("""
                SELECT COUNT(*) FROM properia.advertiser_calendar_connections
                WHERE advertiser_id = :adv AND provider = 'google_calendar'
                """)
            .param("adv", advertiserId)
            .query(Integer.class)
            .single();
        assert count == 0 : "Expected row to be deleted, found " + count;
    }

    // ── GET /google/connect: redirect ─────────────────────────────────────────

    @Test
    void initiateConnect_withoutClientId_redirectsWithError() {
        // Google Calendar client-id is empty in test profile → redirect with error
        given()
            .cookie("properia_session", agentToken)
            .redirects().follow(false)
        .when()
            .get("/api/advertiser/calendar/google/connect?next=/anunciante/visitas")
        .then()
            .statusCode(302)
            .header("Location", containsString("calendar_error=not_configured"));
    }

    // ── Unauthenticated ───────────────────────────────────────────────────────

    @Test
    void unauthenticated_getConnection_returns401() {
        given()
        .when()
            .get("/api/advertiser/calendar/google")
        .then()
            .statusCode(401);
    }

    @Test
    void unauthenticated_disconnect_returns401() {
        given()
        .when()
            .delete("/api/advertiser/calendar/google")
        .then()
            .statusCode(401);
    }

    // ── Missing advertiser access ─────────────────────────────────────────────

    @Test
    void noAdvertiserAccess_returns403() {
        var buyerToken = generateToken(UUID.randomUUID(), "buyer");
        given()
            .cookie("properia_session", buyerToken)
        .when()
            .get("/api/advertiser/calendar/google")
        .then()
            .statusCode(403);
    }
}
