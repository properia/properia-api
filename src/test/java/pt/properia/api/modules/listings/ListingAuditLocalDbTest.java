package pt.properia.api.modules.listings;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A auditoria só vale se escrever mesmo. Estes testes correm o PATCH pela API
 * real e vão depois ler {@code listing_audit} — não há mocks pelo meio, porque
 * o que já falhou antes foi precisamente a persistência: a tabela existe desde a
 * V1 e nunca teve uma linha.
 *
 * <p>O caso central é o dano colateral: um PATCH que envia um campo e apaga
 * outro. Já aconteceu duas vezes em produção (a descrição gerada por IA de um
 * anúncio, os dados de rendimento de um prédio) sem deixar rasto nenhum.
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
@DisplayName("listing_audit — rasto das edições (DB local)")
class ListingAuditLocalDbTest {

    @LocalServerPort private int port;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper json;

    private UUID advertiserId;
    private UUID userId;
    private String token;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        advertiserId = UUID.randomUUID();
        userId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, 'Test Audit Agent', 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """).param("id", userId).param("email", userId + "@test.properia.pt").update();

        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active)
                VALUES (:id, 'private_owner', 'Test Audit Advertiser', true)
                """).param("id", advertiserId).update();

        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, 'owner')
                """).param("adv", advertiserId).param("usr", userId).update();

        token = jwtService.generateToken(new JwtClaims(
            userId, userId + "@test.properia.pt", "Test Audit Agent", "agent",
            null, true, advertiserId, UUID.randomUUID()));
    }

    @AfterEach
    void cleanup() {
        for (var table : List.of("listing_audit", "listing_zone_snapshots", "listing_ai_vision",
                                 "listing_media", "listing_location", "listing_pricing",
                                 "listing_features", "listing_energy", "listing_commercial")) {
            try {
                jdbc.sql("DELETE FROM properia." + table
                         + " WHERE listing_id IN (SELECT id FROM properia.listings WHERE advertiser_id = :adv)")
                    .param("adv", advertiserId).update();
            } catch (Exception ignored) { /* tabela pode não existir neste ambiente */ }
        }
        for (var sql : List.of(
            "DELETE FROM properia.listings WHERE advertiser_id = :adv",
            "DELETE FROM properia.advertiser_users WHERE advertiser_id = :adv",
            "DELETE FROM properia.advertisers WHERE id = :adv")) {
            try { jdbc.sql(sql).param("adv", advertiserId).update(); } catch (Exception ignored) {}
        }
        try { jdbc.sql("DELETE FROM properia.app_users WHERE id = :usr").param("usr", userId).update(); }
        catch (Exception ignored) {}
    }

    @Test
    @DisplayName("um PATCH normal deixa uma linha com o antes e o depois do campo mudado")
    void patchWritesAuditRow() {
        var id = createListing();

        patch(id, """
            {"title": "Apartamento T3 renovado em Lisboa"}
            """, "wizard");

        var row = latestAudit(id);
        assertThat(row).as("PATCH sem linha de auditoria é o bug original a repetir-se").isNotNull();
        assertThat(row.get("event_type")).isEqualTo("patch");
        assertThat(row.get("change_source")).isEqualTo("wizard");
        assertThat((UUID) row.get("changed_by")).isEqualTo(userId);

        var before = fields(row, "payload_before");
        var after = fields(row, "payload_after");
        assertThat(before.get("title")).isEqualTo("Apartamento T3 em Lisboa");
        assertThat(after.get("title")).isEqualTo("Apartamento T3 renovado em Lisboa");
    }

    @Test
    @DisplayName("um PATCH que não muda nada não polui a tabela")
    void noOpPatchWritesNothing() {
        var id = createListing();

        patch(id, """
            {"title": "Apartamento T3 em Lisboa"}
            """, "wizard");

        assertThat(latestAudit(id))
            .as("gravar sem alterar não é um evento; se registarmos, o ruído esconde o que importa")
            .isNull();
    }

    @Test
    @DisplayName("marca como dano colateral o campo apagado que o cliente nunca enviou")
    void collateralWipeIsFlagged() {
        var id = createListing();

        // Só a cidade vai no corpo. Mas PatchListingService reescreve a
        // sub-entidade listing_location por inteiro, e a rua — que ninguém tocou —
        // desaparece com ela.
        patch(id, """
            {"city": "Gondomar"}
            """, "wizard");

        var row = latestAudit(id);
        assertThat(row).isNotNull();
        assertThat(row.get("event_type"))
            .as("um apagamento não pedido tem de ser distinguível numa query de uma linha")
            .isEqualTo("patch.collateral_wipe");

        var after = payload(row, "payload_after");
        assertThat(asList(after.get("wiped"))).contains("street");
        assertThat(asList(after.get("unsent")))
            .as("o cliente enviou city; tudo o resto que mudou foi colateral")
            .contains("street").doesNotContain("city");
        assertThat(asList(after.get("requestKeys"))).containsExactly("city");

        assertThat(fields(row, "payload_before").get("street")).isEqualTo("Avenida da República");
    }

    @Test
    @DisplayName("a origem da edição fica registada e distingue wizard de painel")
    void changeSourceIsRecorded() {
        var id = createListing();

        patch(id, "{\"status\": \"paused\"}", "listings_browser");

        assertThat(latestAudit(id).get("change_source")).isEqualTo("listings_browser");
    }

    @Test
    @DisplayName("um PATCH rejeitado não deixa auditoria de uma mudança que não houve")
    void rejectedPatchWritesNothing() {
        var id = createListing();

        withAuth().header("X-Properia-Edit-Source", "wizard")
            .body("{\"status\": \"estado_que_nao_existe\"}")
            .patch("/api/advertiser/listings/" + id)
            .then().statusCode(400);

        assertThat(latestAudit(id)).isNull();
    }

    @Test
    @DisplayName("GET devolve os campos das sub-entidades — é daqui que o wizard carrega o formulário")
    void getForEditReturnsSubEntityFields() {
        var id = createListing();

        // Este endpoint devolvia rua, código postal e certificado energético sempre
        // a null: o RowMapper preenchia a resposta mas devolvia null, e `optional()`
        // lia isso como "não há linha", pelo que o ramo de fallback corria a seguir
        // e repunha tudo a null. O wizard abria com os campos vazios e gravava-os
        // vazios. Um GET errado é o que fazia o PATCH destruir dados.
        withAuth().get("/api/advertiser/listings/" + id)
            .then().statusCode(200)
            .body("data.street", org.hamcrest.Matchers.equalTo("Avenida da República"))
            .body("data.locationPrecision", org.hamcrest.Matchers.equalTo("street"))
            .body("data.municipality", org.hamcrest.Matchers.equalTo("Lisboa"))
            .body("data.energyRating", org.hamcrest.Matchers.equalTo("B"));
    }

    @Test
    @DisplayName("abrir e gravar sem mexer em nada não altera o anúncio")
    void loadAndSaveRoundTripPreservesEverything() {
        var id = createListing();

        // O que o wizard faz: GET, o utilizador muda um campo, PATCH do formulário
        // todo. Se o GET perder um campo, o PATCH escreve-o a null — e é assim que
        // se apaga a descrição de um anúncio ao corrigir o preço.
        var loaded = withAuth().get("/api/advertiser/listings/" + id)
            .then().statusCode(200).extract().jsonPath().getMap("data");

        var payload = new java.util.LinkedHashMap<String, Object>();
        for (var key : List.of("title", "descriptionRaw", "street", "postalCode", "city",
                               "district", "parish", "municipality", "locationPrecision",
                               "energyRating", "priceAmount", "bedrooms")) {
            payload.put(key, loaded.get(key));
        }
        payload.put("priceAmount", 340000);

        withAuth().header("X-Properia-Edit-Source", "wizard").body(payload)
            .patch("/api/advertiser/listings/" + id).then().statusCode(200);

        var row = latestAudit(id);
        assertThat(row).isNotNull();
        assertThat(row.get("event_type"))
            .as("gravar o formulário tal como veio não pode apagar nada")
            .isEqualTo("patch");

        var changed = fields(row, "payload_after").keySet();
        assertThat(changed).contains("priceAmount");
        assertThat(changed)
            .as("só o preço foi mexido; %s também mudou", changed)
            .doesNotContain("descriptionRaw", "street", "postalCode", "energyRating");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createListing() {
        var id = withAuth().body("""
            {
              "businessType": "sale",
              "propertyType": "apartment",
              "title": "Apartamento T3 em Lisboa",
              "descriptionRaw": "Apartamento com vistas para o rio.",
              "priceAmount": 350000,
              "bedrooms": 3,
              "bathrooms": 2,
              "usableAreaM2": 120,
              "city": "Lisboa",
              "district": "Lisboa",
              "municipality": "Lisboa",
              "parish": "Avenidas Novas",
              "street": "Avenida da República",
              "postalCode": "1050-191",
              "latitude": 38.7317,
              "longitude": -9.1418,
              "locationPrecision": "street",
              "energyRating": "B"
            }
            """).post("/api/advertiser/listings")
            .then().statusCode(201)
            .extract().jsonPath().getString("data.id");
        return UUID.fromString(id);
    }

    private void patch(UUID id, String body, String source) {
        withAuth().header("X-Properia-Edit-Source", source).body(body)
            .patch("/api/advertiser/listings/" + id)
            .then().statusCode(200);
    }

    private io.restassured.specification.RequestSpecification withAuth() {
        return given().contentType(ContentType.JSON).cookie("properia_session", token);
    }

    private Map<String, Object> latestAudit(UUID listingId) {
        return jdbc.sql("""
                SELECT event_type, change_source, changed_by, payload_before::text AS payload_before,
                       payload_after::text AS payload_after
                FROM properia.listing_audit WHERE listing_id = :id
                ORDER BY created_at DESC LIMIT 1
                """)
            .param("id", listingId)
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("event_type", rs.getString("event_type"));
                m.put("change_source", rs.getString("change_source"));
                m.put("changed_by", rs.getObject("changed_by", UUID.class));
                m.put("payload_before", rs.getString("payload_before"));
                m.put("payload_after", rs.getString("payload_after"));
                return m;
            })
            .optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> row, String column) {
        try {
            return json.readValue((String) row.get(column), Map.class);
        } catch (Exception e) {
            throw new AssertionError("payload jsonb ilegível: " + row.get(column), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fields(Map<String, Object> row, String column) {
        return (Map<String, Object>) payload(row, column).get("fields");
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object v) {
        return v == null ? List.of() : (List<String>) v;
    }
}
