package pt.properia.api.modules.listings;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Full E2E flow test against the local dev PostgreSQL (docker-compose).
 *
 * Covers:
 *  1. Listing creation — verifies FK flush fix (no exception on listing_location INSERT)
 *  2. Sub-entities (listing_location, listing_pricing) persisted in same transaction
 *  3. Zone enrichment — POST triggers job enqueue → job_executions row created
 *  4. Zone status endpoint — structured JSON response
 *  5. Vision enrichment — returns 200 or 503 (depends on OPENAI_API_KEY env)
 *  6. Virtual tour — trigger returns 202 or 422 (no images = 422)
 *  7. Full sequential flow validation
 *
 * Uses the local dev PostgreSQL at localhost:5432.
 * Run with: docker-compose up -d postgres (from properia-api directory).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/properia?currentSchema=properia",
    "spring.datasource.username=properia",
    "spring.datasource.password=properia_dev_password",
    "spring.flyway.url=jdbc:postgresql://localhost:5432/properia",
    "spring.flyway.user=properia",
    "spring.flyway.password=properia_dev_password",
    "spring.flyway.baseline-on-migrate=true",
    "properia.security.internal-api-secret=",
})
@DisplayName("Listing full flow — local DB (no Testcontainers)")
class ListingFlowLocalDbTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID userId;
    private String agentToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        advertiserId = UUID.randomUUID();
        userId = UUID.randomUUID();

        // Insert app_users row
        jdbc.sql("""
                INSERT INTO properia.app_users
                  (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, :name, 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """)
            .param("id", userId)
            .param("email", userId + "@test.properia.pt")
            .param("name", "Test Agent Flow")
            .update();

        // Insert advertisers row
        jdbc.sql("""
                INSERT INTO properia.advertisers
                  (id, advertiser_type, legal_name, is_active)
                VALUES (:id, 'private_owner', 'Test Flow Advertiser', true)
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

        // Generate JWT
        agentToken = jwtService.generateToken(new JwtClaims(
            userId,
            userId + "@test.properia.pt",
            "Test Agent Flow",
            "agent",
            null,
            true,
            advertiserId,
            UUID.randomUUID()
        ));
    }

    @AfterEach
    void cleanup() {
        // Delete in reverse FK order
        try {
            jdbc.sql("DELETE FROM properia.listing_zone_snapshots WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listing_ai_vision WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listing_media WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listing_location WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listing_pricing WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listing_commercial WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.job_executions WHERE entity_id IN (SELECT id::text::uuid FROM properia.listings WHERE advertiser_id = :adv)").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.listings WHERE advertiser_id = :adv").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.advertiser_users WHERE advertiser_id = :adv").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.advertisers WHERE id = :adv").param("adv", advertiserId).update();
        } catch (Exception ignored) {}
        try {
            jdbc.sql("DELETE FROM properia.app_users WHERE id = :usr").param("usr", userId).update();
        } catch (Exception ignored) {}
    }

    // ── 1. Listing creation ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/advertiser/listings — cria imóvel sem FK violation (flush fix)")
    void createListing_noFkViolation() {
        createListingReq()
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
    @DisplayName("listing_location sub-entity inserida (FK flush fix)")
    void createListing_locationSubEntityPersisted() {
        var id = extractId(createListingReq().then().statusCode(201));

        var count = jdbc.sql("SELECT COUNT(*) FROM properia.listing_location WHERE listing_id = :id")
            .param("id", id).query(Integer.class).single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("listing_pricing sub-entity inserida")
    void createListing_pricingSubEntityPersisted() {
        var id = extractId(createListingReq().then().statusCode(201));

        var count = jdbc.sql("SELECT COUNT(*) FROM properia.listing_pricing WHERE listing_id = :id")
            .param("id", id).query(Integer.class).single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("POST sem auth → 401")
    void createListing_noAuth_401() {
        given().contentType(ContentType.JSON).body(listingJson())
            .post("/api/advertiser/listings")
        .then()
            .statusCode(anyOf(is(401), is(403)));
    }

    @Test
    @DisplayName("POST sem título → 400")
    void createListing_missingTitle_400() {
        withAuth()
            .body("""
                {"businessType":"sale","propertyType":"apartment","priceAmount":100000}
                """)
        .post("/api/advertiser/listings")
        .then()
            .statusCode(400);
    }

    // ── 2. List ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/advertiser/listings — mostra o imóvel criado")
    void listListings_showsCreatedListing() {
        createListingReq().then().statusCode(201);

        withAuth().get("/api/advertiser/listings")
            .then().statusCode(200)
            .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── 3. Zone enrichment ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /zone → 202 com job enqueue")
    void zoneEnrichment_202() {
        var id = extractId(createListingReq().then().statusCode(201));

        withAuth().post("/api/enrichment/listings/{id}/zone", id)
            .then()
            .statusCode(202)
            .body("data.queued",  equalTo(true))
            .body("data.jobType", equalTo("listing_zone_enrichment"));
    }

    @Test
    @DisplayName("POST /zone → job_executions row criada")
    void zoneEnrichment_jobExecutionCreated() {
        var id = extractId(createListingReq().then().statusCode(201));
        withAuth().post("/api/enrichment/listings/{id}/zone", id);

        var count = jdbc.sql("""
                SELECT COUNT(*) FROM properia.job_executions
                WHERE entity_id = :id AND job_type = 'listing_zone_enrichment'
                """).param("id", id).query(Integer.class).single();

        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /zone/status → resposta estruturada")
    void zoneStatus_structuredResponse() {
        var id = extractId(createListingReq().then().statusCode(201));
        withAuth().post("/api/enrichment/listings/{id}/zone", id);

        withAuth().get("/api/enrichment/listings/{id}/zone/status", id)
            .then()
            .statusCode(200)
            .body("data.listingId",           equalTo(id.toString()))
            .body("data.zoneProcessingStatus", notNullValue())
            .body("data.latestJob",            notNullValue())
            .body("data.latestJob.status",     notNullValue());
    }

    @Test
    @DisplayName("POST /zone sem coordenadas → 422 missing_coordinates")
    void zoneEnrichment_missingCoords_422() {
        var id = extractId(
            withAuth()
                .body("""
                    {"businessType":"sale","propertyType":"apartment",
                     "title":"Sem coords","priceAmount":100000}
                    """)
                .post("/api/advertiser/listings")
                .then().statusCode(201)
        );

        withAuth().post("/api/enrichment/listings/{id}/zone", id)
            .then()
            .statusCode(422)
            .body("data.queued", equalTo(false))
            .body("data.reason", equalTo("missing_coordinates"));
    }

    // ── 4. Vision enrichment ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /vision/status → resposta estruturada")
    void visionStatus_structuredResponse() {
        var id = extractId(createListingReq().then().statusCode(201));

        withAuth().get("/api/enrichment/listings/{id}/vision/status", id)
            .then()
            .statusCode(200)
            .body("data.listingId",       equalTo(id.toString()))
            .body("data.hasVisionSummary", notNullValue())
            .body("data.latestJob",        notNullValue());
    }

    @Test
    @DisplayName("POST /vision → 200 (com chave) ou 503 (sem chave)")
    void visionEnrichment_200or503() {
        var id = extractId(createListingReq().then().statusCode(201));

        var code = withAuth()
            .post("/api/enrichment/listings/{id}/vision", id)
            .then().extract().statusCode();

        assertThat(code).as("Vision deve retornar 200 (chave OK) ou 503 (sem chave)").isIn(200, 503);
    }

    // ── 5. Virtual tour ───────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /virtual-tour/generate → 202 ou 422 (sem imagens/quota)")
    void virtualTour_generate_202or422() {
        var id = extractId(createListingReq().then().statusCode(201));

        withAuth()
            .post("/api/advertiser/listings/{id}/virtual-tour/generate", id)
            .then()
            .statusCode(anyOf(is(202), is(422)));
    }

    @Test
    @DisplayName("GET /virtual-tour → status object")
    void virtualTour_status_200() {
        var id = extractId(createListingReq().then().statusCode(201));

        withAuth()
            .get("/api/advertiser/listings/{id}/virtual-tour", id)
            .then()
            .statusCode(200)
            .body("data", notNullValue());
    }

    // ── 6. Full sequential flow ───────────────────────────────────────────────

    @Test
    @DisplayName("Fluxo completo: criar → sub-entities no DB → zone enqueue → job criado → poll status")
    void fullFlow() {
        // Step 1: Create
        var resp = createListingReq().then().statusCode(201);
        var id = extractId(resp);

        // Step 2: Sub-entities no DB (FK flush fix verificado)
        assertThat(jdbc.sql("SELECT COUNT(*) FROM properia.listing_location WHERE listing_id = :id")
            .param("id", id).query(Integer.class).single()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM properia.listing_pricing WHERE listing_id = :id")
            .param("id", id).query(Integer.class).single()).isEqualTo(1);

        // Step 3: Zone enrichment enqueue
        withAuth().post("/api/enrichment/listings/{id}/zone", id)
            .then().statusCode(202);

        // Step 4: Job criado no DB
        var jobStatus = jdbc.sql("""
                SELECT status FROM properia.job_executions
                WHERE entity_id = :id AND job_type = 'listing_zone_enrichment'
                ORDER BY created_at DESC LIMIT 1
                """).param("id", id).query(String.class).optional();

        assertThat(jobStatus).isPresent();

        // Step 5: Poll zone status
        withAuth().get("/api/enrichment/listings/{id}/zone/status", id)
            .then()
            .statusCode(200)
            .body("data.listingId", equalTo(id.toString()))
            .body("data.latestJob.status", notNullValue());

        // Step 6: Poll vision status
        withAuth().get("/api/enrichment/listings/{id}/vision/status", id)
            .then()
            .statusCode(200)
            .body("data.listingId", equalTo(id.toString()));

        // Step 7: Virtual tour status
        withAuth().get("/api/advertiser/listings/{id}/virtual-tour", id)
            .then()
            .statusCode(200);

        // Step 8: Listing ainda acessível
        withAuth().get("/api/advertiser/listings")
            .then()
            .statusCode(200)
            .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private io.restassured.response.Response createListingReq() {
        return withAuth().body(listingJson())
            .post("/api/advertiser/listings");
    }

    private String listingJson() {
        return """
            {
              "businessType": "sale",
              "propertyType": "apartment",
              "title": "Apartamento T3 em Lisboa",
              "descriptionRaw": "Apartamento com vistas para o rio.",
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

    private io.restassured.specification.RequestSpecification withAuth() {
        return given()
            .contentType(ContentType.JSON)
            .cookie("properia_session", agentToken);
    }

    private UUID extractId(ValidatableResponse resp) {
        return UUID.fromString(resp.extract().jsonPath().getString("data.id"));
    }
}
