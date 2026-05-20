package pt.properia.api.shared;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.util.UUID;

/**
 * Base class for all integration tests.
 *
 * - Starts a real PostgreSQL 16 container via Testcontainers
 * - Flyway runs all migrations on startup
 * - RestAssured configured against the running server port
 * - Helper methods: authCookie(), adminCookie(), advertiserCookie()
 *
 * Tests extending this class hit a real DB — no mocks.
 * This is intentional: we were burned by mocks masking DB issues.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("properia_test")
        .withUsername("properia")
        .withPassword("properia_test_password")
        .withInitScript("db/init/000_extensions.sql");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
            POSTGRES.getJdbcUrl() + "&currentSchema=properia");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JwtService jwtService;

    @BeforeEach
    void setUpRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    // ── Auth helpers ───────────────────────────────────────────────────────────

    protected String generateToken(UUID userId, String role) {
        return generateToken(userId, role, false, null);
    }

    protected String generateToken(UUID userId, String role,
                                    boolean hasAdvertiserAccess, UUID advertiserId) {
        var claims = new JwtClaims(
            userId,
            userId + "@test.properia.pt",
            "Test User",
            role,
            null,
            hasAdvertiserAccess,
            advertiserId,
            UUID.randomUUID()
        );
        return jwtService.generateToken(claims);
    }

    protected io.restassured.specification.RequestSpecification withAuth(String token) {
        return RestAssured.given()
            .contentType(ContentType.JSON)
            .cookie("properia_session", token);
    }

    protected io.restassured.specification.RequestSpecification asBuyer() {
        return withAuth(generateToken(UUID.randomUUID(), "buyer"));
    }

    protected io.restassured.specification.RequestSpecification asAgent(UUID advertiserId) {
        return withAuth(generateToken(UUID.randomUUID(), "agent", true, advertiserId));
    }

    protected io.restassured.specification.RequestSpecification asAdmin() {
        return withAuth(generateToken(UUID.randomUUID(), "platform_admin"));
    }

    protected io.restassured.specification.RequestSpecification asGuest() {
        return RestAssured.given().contentType(ContentType.JSON);
    }
}
