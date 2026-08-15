package pt.properia.api.modules.search;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * O filtro de estado/mobília na pesquisa lia {@code condition_final} /
 * {@code furnished_final} — colunas que se destinavam a uma reconciliação
 * declarado+IA que nunca chegou a ser implementada (nenhum setConditionFinal em
 * todo o backend). Ficam sempre NULL, e o filtro devolvia sempre zero
 * resultados, para qualquer anúncio do site, mesmo com o anunciante a declarar
 * "remodelado" ou "novo" correctamente.
 *
 * <p>Confirmado ao vivo em produção antes da correção: 13 anúncios publicados,
 * qualquer valor de conditionStatus devolvia total=0.
 *
 * <p>Este teste cria um anúncio directamente na base de dados — como o
 * anunciante o deixaria, com o declarado preenchido e o final nunca tocado — e
 * confirma que o endpoint público de pesquisa o encontra pelo estado
 * declarado, e não o encontra por um estado que não é o seu.
 *
 * <p>Requer o PostgreSQL local: {@code docker-compose up -d postgres}.
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
@DisplayName("Pesquisa por estado/mobília — condition_final e furnished_final nunca escritos (DB local)")
class ListingConditionFilterLocalDbTest {

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID listingId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        advertiserId = UUID.randomUUID();
        listingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active)
                VALUES (:id, 'private_owner', 'Test Condition Filter Advertiser', true)
                """).param("id", advertiserId).update();

        // Exactamente como o anunciante o deixa: declarado preenchido,
        // condition_final/furnished_final NUNCA tocados (NULL) — nenhum código no
        // backend os escreve.
        jdbc.sql("""
                INSERT INTO properia.listings
                  (id, public_id, advertiser_id, business_type, property_type, title, title_normalized,
                   status, condition_declared, furnished_declared, city, price_amount)
                VALUES
                  (:id, :pub, :adv, 'sale', 'apartment', 'Apartamento T3 remodelado em Lisboa',
                   'apartamento t3 remodelado em lisboa', 'published', 'remodeled', 'furnished', 'Lisboa', 350000)
                """)
            .param("id", listingId).param("pub", listingId.toString().substring(0, 8))
            .param("adv", advertiserId).update();
    }

    @AfterEach
    void cleanup() {
        try { jdbc.sql("DELETE FROM properia.listings WHERE advertiser_id = :adv").param("adv", advertiserId).update(); } catch (Exception ignored) {}
        try { jdbc.sql("DELETE FROM properia.advertisers WHERE id = :adv").param("adv", advertiserId).update(); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("encontra o anúncio pelo estado DECLARADO, apesar de condition_final estar NULL")
    void findsListingByDeclaredCondition() {
        var body = given().contentType(ContentType.JSON)
            .queryParam("conditionStatus", "remodeled")
            .get("/api/search/listings")
            .then().statusCode(200)
            .extract().jsonPath();

        assertThat((Integer) body.get("data.total"))
            .as("o filtro tem de encontrar um anúncio com condition_declared='remodeled', "
                + "mesmo com condition_final NULL — era exactamente isto que devolvia sempre zero")
            .isGreaterThanOrEqualTo(1);

        var ids = body.getList("data.items.id", String.class);
        assertThat(ids).contains(listingId.toString());
    }

    @Test
    @DisplayName("não encontra o anúncio por um estado que não é o seu")
    void doesNotFindListingByWrongCondition() {
        var body = given().contentType(ContentType.JSON)
            .queryParam("conditionStatus", "new")
            .get("/api/search/listings")
            .then().statusCode(200)
            .extract().jsonPath();

        var ids = body.getList("data.items.id", String.class);
        assertThat(ids)
            .as("o teste não pode passar só porque o filtro deixou de filtrar — tem de excluir corretamente")
            .doesNotContain(listingId.toString());
    }

    @Test
    @DisplayName("encontra o anúncio pela mobília DECLARADA, apesar de furnished_final estar NULL")
    void findsListingByDeclaredFurnishedStatus() {
        var body = given().contentType(ContentType.JSON)
            .queryParam("mobilia", "mobilado")
            .get("/api/search/listings")
            .then().statusCode(200)
            .extract().jsonPath();

        var ids = body.getList("data.items.id", String.class);
        assertThat(ids).contains(listingId.toString());
    }
}
