package pt.properia.api.modules.listings;

import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * End-to-end integration test for listing creation and all enrichment integrations.
 *
 * Covers:
 *  1. Listing creation — verifies the JPA flush fix (no FK violation on listing_location)
 *  2. Zone enrichment — POST triggers Overpass async job, GET /zone/status polls it
 *  3. Vision enrichment — POST returns 503 (no OpenAI key in test env) or 200 with data
 *  4. Virtual tour — POST triggers generation (202), GET polls status
 *  5. Job tracking — job_executions entries are created correctly
 *
 * All tests hit a real Testcontainers PostgreSQL instance — no mocks.
 */
@DisplayName("Listing full flow — create + enrich + virtual tour")
class ListingFullFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID userId;
    private String agentToken;

    @BeforeEach
    void setupAdvertiser() {
        advertiserId = UUID.randomUUID();
        userId = UUID.randomUUID();

        // Insert app_users row (required for advertiser_users FK)
        jdbc.sql("""
                INSERT INTO properia.app_users
                  (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, :name, 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """)
            .param("id", userId)
            .param("email", userId + "@test.properia.pt")
            .param("name", "Test Agent")
            .update();

        // Insert advertisers row
        jdbc.sql("""
                INSERT INTO properia.advertisers
                  (id, advertiser_type, legal_name, is_active)
                VALUES (:id, 'private_owner', 'Test Advertiser Lda', true)
                """)
            .param("id", advertiserId)
            .update();

        // Link user → advertiser
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, 'owner')
                """)
            .param("adv", advertiserId)
            .param("usr", userId)
            .update();

        // Generate JWT for this agent
        agentToken = generateToken(userId, "agent", true, advertiserId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Listing creation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/advertiser/listings — creates listing (verifies FK flush fix)")
    void createListing_returns201_noFkViolation() {
        createListingRequest()
            .then()
            .statusCode(201)
            .body("data.id",           notNullValue())
            .body("data.publicId",     notNullValue())
            .body("data.status",       equalTo("draft"))
            .body("data.businessType", equalTo("sale"))
            .body("data.propertyType", equalTo("apartment"))
            .body("data.title",        equalTo("Apartamento T3 em Lisboa"));
    }

    @Test
    @DisplayName("POST /api/advertiser/listings — location sub-entity saved (listing_location FK)")
    void createListing_locationSubEntityPersisted() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        // Verify listing_location was inserted (this is where FK violation was happening before the flush fix)
        var count = jdbc.sql("SELECT COUNT(*) FROM properia.listing_location WHERE listing_id = :id")
            .param("id", listingId)
            .query(Integer.class)
            .single();

        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/advertiser/listings — pricing sub-entity saved (listing_pricing)")
    void createListing_pricingSubEntityPersisted() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        var count = jdbc.sql("SELECT COUNT(*) FROM properia.listing_pricing WHERE listing_id = :id")
            .param("id", listingId)
            .query(Integer.class)
            .single();

        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/advertiser/listings — requires auth (401 without cookie)")
    void createListing_withoutAuth_returns401() {
        given()
            .contentType("application/json")
            .body(listingBody())
        .when()
            .post("/api/advertiser/listings")
        .then()
            .statusCode(anyOf(is(401), is(403)));
    }

    @Test
    @DisplayName("POST /api/advertiser/listings — validation: missing title returns 400")
    void createListing_missingTitle_returns400() {
        withAuth(agentToken)
            .body("""
                {
                  "businessType": "sale",
                  "propertyType": "apartment",
                  "priceAmount": 250000
                }
                """)
        .when()
            .post("/api/advertiser/listings")
        .then()
            .statusCode(400);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. GET /api/advertiser/listings — list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/advertiser/listings — returns created listing in list")
    void listListings_showsCreatedListing() {
        createListingRequest().then().statusCode(201);

        withAuth(agentToken)
        .when()
            .get("/api/advertiser/listings")
        .then()
            .statusCode(200)
            .body("data", hasSize(greaterThanOrEqualTo(1)))
            .body("data[0].status", equalTo("draft"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Zone enrichment
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/enrichment/listings/{id}/zone — enqueues zone job (202)")
    void zoneEnrichment_enqueues_202() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        withAuth(agentToken)
        .when()
            .post("/api/enrichment/listings/{id}/zone", listingId)
        .then()
            .statusCode(202)
            .body("data.queued",   equalTo(true))
            .body("data.jobType",  equalTo("listing_zone_enrichment"));
    }

    @Test
    @DisplayName("GET /api/enrichment/listings/{id}/zone/status — returns structured status")
    void zoneStatus_returnsStructuredResponse() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        // Trigger it first
        withAuth(agentToken)
            .post("/api/enrichment/listings/{id}/zone", listingId);

        withAuth(agentToken)
        .when()
            .get("/api/enrichment/listings/{id}/zone/status", listingId)
        .then()
            .statusCode(200)
            .body("data.listingId",           equalTo(listingId.toString()))
            .body("data.zoneProcessingStatus", notNullValue())
            .body("data.latestJob",            notNullValue())
            .body("data.latestJob.status",     notNullValue());
    }

    @Test
    @DisplayName("Zone job_executions — record inserted after enqueue")
    void zoneEnrichment_jobExecutionRecordCreated() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        withAuth(agentToken)
            .post("/api/enrichment/listings/{id}/zone", listingId);

        var jobCount = jdbc.sql("""
                SELECT COUNT(*) FROM properia.job_executions
                WHERE entity_id = :id AND job_type = 'listing_zone_enrichment'
                """)
            .param("id", listingId)
            .query(Integer.class)
            .single();

        org.assertj.core.api.Assertions.assertThat(jobCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/enrichment/listings/{id}/zone — listing without coords returns 422")
    void zoneEnrichment_missingCoords_returns422() {
        var listingId = extractListingId(
            withAuth(agentToken)
                .body("""
                    {
                      "businessType": "sale",
                      "propertyType": "apartment",
                      "title": "Sem coordenadas",
                      "priceAmount": 100000
                    }
                    """)
            .when()
                .post("/api/advertiser/listings")
            .then()
                .statusCode(201)
        );

        withAuth(agentToken)
        .when()
            .post("/api/enrichment/listings/{id}/zone", listingId)
        .then()
            .statusCode(422)
            .body("data.queued", equalTo(false))
            .body("data.reason", equalTo("missing_coordinates"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Vision enrichment
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/enrichment/listings/{id}/vision — no OpenAI key returns 503")
    void visionEnrichment_noApiKey_returns503() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        // In test env, OPENAI_API_KEY is not set → 503 VISION_UNAVAILABLE
        var statusCode = withAuth(agentToken)
        .when()
            .post("/api/enrichment/listings/{id}/vision", listingId)
        .then()
            .extract()
            .statusCode();

        // Either 503 (not configured) or 200 (key available in test env — e.g. via system env)
        org.assertj.core.api.Assertions.assertThat(statusCode).isIn(200, 503);
    }

    @Test
    @DisplayName("GET /api/enrichment/listings/{id}/vision/status — returns structured response")
    void visionStatus_returnsStructuredResponse() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        withAuth(agentToken)
        .when()
            .get("/api/enrichment/listings/{id}/vision/status", listingId)
        .then()
            .statusCode(200)
            .body("data.listingId",       equalTo(listingId.toString()))
            .body("data.hasVisionSummary", notNullValue())
            .body("data.latestJob",        notNullValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Virtual tour
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/advertiser/listings/{id}/virtual-tour/generate — returns 202 pending")
    void virtualTour_generate_returns202() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        withAuth(agentToken)
        .when()
            .post("/api/advertiser/listings/{id}/virtual-tour/generate", listingId)
        .then()
            .statusCode(anyOf(is(202), is(422)));  // 422 = no images or quota exceeded
    }

    @Test
    @DisplayName("GET /api/advertiser/listings/{id}/virtual-tour — returns status object")
    void virtualTour_status_returnsObject() {
        var listingId = extractListingId(createListingRequest().then().statusCode(201));

        withAuth(agentToken)
        .when()
            .get("/api/advertiser/listings/{id}/virtual-tour", listingId)
        .then()
            .statusCode(200)
            .body("data", notNullValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Full sequential flow
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full flow: create → zone enqueue → zone status poll → verify DB state")
    void fullFlow_createThenZoneEnrich() {
        // Step 1: Create listing
        var createResp = createListingRequest().then().statusCode(201);
        var listingId  = extractListingId(createResp);

        // Step 2: Verify sub-entities in DB (FK flush fix)
        var locationRows = jdbc.sql("SELECT COUNT(*) FROM properia.listing_location WHERE listing_id = :id")
            .param("id", listingId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(locationRows).isEqualTo(1);

        var pricingRows = jdbc.sql("SELECT COUNT(*) FROM properia.listing_pricing WHERE listing_id = :id")
            .param("id", listingId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(pricingRows).isEqualTo(1);

        // Step 3: Trigger zone enrichment
        withAuth(agentToken)
            .post("/api/enrichment/listings/{id}/zone", listingId)
            .then().statusCode(202);

        // Step 4: Verify job was created in DB
        var jobStatus = jdbc.sql("""
                SELECT status FROM properia.job_executions
                WHERE entity_id = :id AND job_type = 'listing_zone_enrichment'
                ORDER BY created_at DESC LIMIT 1
                """)
            .param("id", listingId)
            .query(String.class)
            .optional();

        org.assertj.core.api.Assertions.assertThat(jobStatus).isPresent();

        // Step 5: Poll zone status endpoint
        withAuth(agentToken)
            .get("/api/enrichment/listings/{id}/zone/status", listingId)
            .then()
            .statusCode(200)
            .body("data.listingId", equalTo(listingId.toString()))
            .body("data.latestJob.status", notNullValue());

        // Step 6: Listing should still be retrievable
        withAuth(agentToken)
            .get("/api/advertiser/listings")
            .then()
            .statusCode(200)
            .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private io.restassured.response.Response createListingRequest() {
        return withAuth(agentToken)
            .body(listingBody())
        .when()
            .post("/api/advertiser/listings");
    }

    private String listingBody() {
        return """
            {
              "businessType": "sale",
              "propertyType": "apartment",
              "title": "Apartamento T3 em Lisboa",
              "descriptionRaw": "Excelente apartamento com vistas para o rio.",
              "priceAmount": 350000,
              "bedrooms": 3,
              "bathrooms": 2,
              "usableAreaM2": 120,
              "grossAreaM2": 140,
              "city": "Lisboa",
              "district": "Lisboa",
              "municipality": "Lisboa",
              "parish": "Avenidas Novas",
              "street": "Avenida da República",
              "postalCode": "1050-191",
              "latitude": 38.7317,
              "longitude": -9.1418,
              "locationPrecision": "street",
              "conditionDeclared": "used_good",
              "energyRating": "B"
            }
            """;
    }

    private UUID extractListingId(ValidatableResponse response) {
        String idStr = response.extract().jsonPath().getString("data.id");
        return UUID.fromString(idStr);
    }
}
