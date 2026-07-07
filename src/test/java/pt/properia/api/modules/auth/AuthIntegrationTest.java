package pt.properia.api.modules.auth;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import pt.properia.api.shared.IntegrationTestBase;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void register_creates_user_and_returns_201() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"name":"Test User","email":"test@example.com","password":"Password123","marketingConsent":false}
                """)
        .when()
            .post("/api/auth/register")
        .then()
            .statusCode(201)
            .body("data.user.email", equalTo("test@example.com"))
            .body("data.user.name", equalTo("Test User"))
            .body("data.requiresEmailVerification", equalTo(true));
    }

    @Test
    void register_duplicate_email_returns_409() {
        String body = """
            {"name":"User A","email":"dup@example.com","password":"Password123","marketingConsent":false}
            """;

        given().contentType(ContentType.JSON).body(body)
            .post("/api/auth/register").then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
            .post("/api/auth/register").then()
            .statusCode(409)
            .body("error.code", equalTo("CONFLICT"));
    }

    @Test
    void login_with_valid_credentials_returns_200_and_cookie() {
        // Register first
        given().contentType(ContentType.JSON)
            .body("""
                {"name":"Login User","email":"login@example.com","password":"Password123","marketingConsent":false}
                """)
            .post("/api/auth/register").then().statusCode(201);

        // Login
        given().contentType(ContentType.JSON)
            .body("""
                {"email":"login@example.com","password":"Password123"}
                """)
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(200)
            .header("Set-Cookie", containsString("properia_session"))
            .body("data.user.id", notNullValue())
            .body("data.user.email", equalTo("login@example.com"))
            // Regressão: "session" tem de trazer hasAdvertiserAccess — é o campo que o
            // frontend usa para decidir redirect pós-login para /anunciante vs /.
            .body("data.session.hasAdvertiserAccess", notNullValue())
            .body("data.session.sub", notNullValue());
    }

    @Test
    void login_with_wrong_password_returns_401() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name":"User X","email":"userx@example.com","password":"Password123","marketingConsent":false}
                """)
            .post("/api/auth/register").then().statusCode(201);

        given().contentType(ContentType.JSON)
            .body("""
                {"email":"userx@example.com","password":"WrongPassword"}
                """)
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(401)
            .body("error.code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void login_with_unknown_email_returns_401() {
        given().contentType(ContentType.JSON)
            .body("""
                {"email":"nobody@example.com","password":"Password123"}
                """)
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(401);
    }

    @Test
    void logout_returns_200_and_clears_cookie() {
        given().contentType(ContentType.JSON)
        .when()
            .post("/api/auth/logout")
        .then()
            .statusCode(200)
            .body("data.ok", equalTo(true));
    }

    @Test
    void me_without_cookie_returns_null() {
        given()
        .when()
            .get("/api/auth/me")
        .then()
            .statusCode(200)
            .body("data", nullValue());
    }

    @Test
    void forgot_password_always_returns_200() {
        given().contentType(ContentType.JSON)
            .body("""
                {"email":"nobody@example.com"}
                """)
        .when()
            .post("/api/auth/password/forgot")
        .then()
            .statusCode(200)
            .body("data.ok", equalTo(true));
    }

    @Test
    void register_with_short_password_returns_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name":"User","email":"u@example.com","password":"short","marketingConsent":false}
                """)
        .when()
            .post("/api/auth/register")
        .then()
            .statusCode(400);
    }
}
