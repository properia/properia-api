package pt.properia.api.modules.health;

import org.junit.jupiter.api.Test;
import pt.properia.api.shared.IntegrationTestBase;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class HealthControllerTest extends IntegrationTestBase {

    @Test
    void health_returns_200_with_ok_status() {
        given()
            .when()
            .get("/api/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("ok"))
            .body("version", notNullValue())
            .body("timestamp", notNullValue());
    }

    @Test
    void health_is_accessible_without_authentication() {
        asGuest()
            .when()
            .get("/api/health")
            .then()
            .statusCode(200);
    }

    @Test
    void unknown_endpoint_returns_404_not_found() {
        asGuest()
            .when()
            .get("/api/nonexistent")
            .then()
            .statusCode(404);
    }
}
