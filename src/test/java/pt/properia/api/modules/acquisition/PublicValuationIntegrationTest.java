package pt.properia.api.modules.acquisition;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.modules.acquisition.application.CreateOwnerValuationRequestUseCase;
import pt.properia.api.shared.IntegrationTestBase;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Endpoints públicos de angariação (/vender), contra base de dados real.
 *
 * O que está a ser protegido, por ordem de gravidade:
 *  1. Um formulário público de escrita sem sessão — se as validações cederem,
 *     entra lixo no CRM e a equipa comercial deixa de confiar no canal.
 *  2. A prova de consentimento RGPD tem de ficar persistida com o texto exato.
 *  3. O lead de proprietário tem de nascer SEM imóvel, o que só é possível desde
 *     a V78; se alguém repuser o NOT NULL, este teste falha imediatamente.
 *
 * Usa uma localização inventada para não depender dos seeds de demonstração.
 */
@DisplayName("Angariação — endpoints públicos de avaliação")
class PublicValuationIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/public/valuation";

    /** Concelho que não existe em Portugal nem nos seeds — a cascata há de falhar por completo. */
    private static final String MUNICIPIO_SEM_DADOS = "Vilarinho-de-Teste-XYZ";

    /** Concelho onde este teste semeia os seus próprios comparáveis. */
    private static final String MUNICIPIO_COM_DADOS = "Vilateste-Comparaveis";
    private static final String FREGUESIA_COM_DADOS = "Freguesia-Teste-Alfa";

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;

    @BeforeEach
    void seedComparables() {
        advertiserId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active, plan_code)
                VALUES (:id, 'agency', 'Agência Angariação Teste Lda', true, 'business')
                """).param("id", advertiserId).update();

        // 5 anúncios publicados a 2.000 €/m² — acima do mínimo de 3, para a
        // freguesia ser aceite como fonte.
        for (int i = 0; i < 5; i++) {
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO properia.listings
                      (id, public_id, advertiser_id, status, business_type, property_type,
                       title, title_normalized, price_amount, usable_area_m2, bedrooms,
                       city, parish, district)
                    VALUES (:id, :pub, :adv, 'published', 'sale', 'apartment',
                            'T2 comparável', 't2 comparavel', 170000, 85, 2,
                            :city, :parish, 'Porto')
                    """)
                .param("id", id)
                .param("pub", "LT-" + id.toString().substring(0, 8))
                .param("adv", advertiserId)
                .param("city", MUNICIPIO_COM_DADOS)
                .param("parish", FREGUESIA_COM_DADOS)
                .update();
        }
    }

    // ── Payloads ──────────────────────────────────────────────────────────────

    private Map<String, Object> validSubmission() {
        var body = new LinkedHashMap<String, Object>();
        body.put("addressRaw", "Rua de Teste 123");
        body.put("postalCode", "4100-100");
        body.put("district", "Porto");
        body.put("municipality", MUNICIPIO_COM_DADOS);
        body.put("parish", FREGUESIA_COM_DADOS);
        body.put("propertyType", "apartment");
        body.put("bedrooms", 2);
        body.put("usableAreaM2", 85);
        body.put("conditionStatus", "used_good");
        body.put("sellingHorizon", "3m");
        body.put("hasAgency", false);
        body.put("contactName", "Maria Proprietária");
        body.put("contactEmail", "maria." + UUID.randomUUID() + "@exemplo.pt");
        body.put("contactPhone", "912345678");
        body.put("consentGranted", true);
        body.put("consentText", "Autorizo o tratamento dos meus dados para efeitos de avaliação.");
        body.put("marketingConsent", false);
        // Preenchimento credível: 30 s atrás.
        body.put("formStartedAt", System.currentTimeMillis() - 30_000);
        body.put("utm", Map.of("utm_source", "google", "utm_campaign", "vender-porto"));
        return body;
    }

    @Nested
    @DisplayName("POST /estimate")
    class Estimate {

        @Test
        @DisplayName("com comparáveis suficientes devolve intervalo e ressalva legal")
        void devolveEstimativa() {
            given().contentType(ContentType.JSON)
                .body(Map.of(
                    "propertyType", "apartment",
                    "municipality", MUNICIPIO_COM_DADOS,
                    "parish", FREGUESIA_COM_DADOS,
                    "bedrooms", 2,
                    "usableAreaM2", 85))
                .when().post(BASE + "/estimate")
                .then().statusCode(200)
                .body("data.available", equalTo(true))
                .body("data.min", notNullValue())
                .body("data.max", notNullValue())
                .body("data.source", equalTo("listings_parish"))
                .body("data.disclaimer", notNullValue());
        }

        @Test
        @DisplayName("sem comparáveis não inventa um valor")
        void semComparaveisNaoDevolveValor() {
            given().contentType(ContentType.JSON)
                .body(Map.of(
                    "propertyType", "apartment",
                    "municipality", MUNICIPIO_SEM_DADOS,
                    "usableAreaM2", 90))
                .when().post(BASE + "/estimate")
                .then().statusCode(200)
                .body("data.available", equalTo(false))
                .body("data.min", nullValue())
                .body("data.source", equalTo("none"));
        }

        @Test
        @DisplayName("não expõe o mapa de auditoria interna ao cliente")
        void naoExpoeInputsInternos() {
            given().contentType(ContentType.JSON)
                .body(Map.of(
                    "propertyType", "apartment",
                    "municipality", MUNICIPIO_COM_DADOS,
                    "parish", FREGUESIA_COM_DADOS,
                    "usableAreaM2", 85))
                .when().post(BASE + "/estimate")
                .then().statusCode(200)
                .body("data.inputs", nullValue())
                .body("data.cascade", nullValue());
        }

        @Test
        @DisplayName("rejeita área ausente ou fora de escala")
        void validaEntrada() {
            given().contentType(ContentType.JSON)
                .body(Map.of("propertyType", "apartment", "municipality", "Porto"))
                .when().post(BASE + "/estimate")
                .then().statusCode(400);

            given().contentType(ContentType.JSON)
                .body(Map.of("propertyType", "apartment", "municipality", "Porto",
                             "usableAreaM2", 999999))
                .when().post(BASE + "/estimate")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("é público — não exige sessão")
        void naoExigeSessao() {
            given().contentType(ContentType.JSON)
                .body(Map.of("propertyType", "apartment", "municipality", MUNICIPIO_SEM_DADOS,
                             "usableAreaM2", 90))
                .when().post(BASE + "/estimate")
                .then().statusCode(200);
        }
    }

    @Nested
    @DisplayName("POST /requests")
    class CreateRequest {

        @Test
        @DisplayName("cria lead de proprietário SEM imóvel e o snapshot associado")
        void criaLeadESnapshot() {
            var body = validSubmission();

            var requestId = given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(201)
                .body("data.requestId", notNullValue())
                .body("data.publicToken", notNullValue())
                .body("data.verificationRequired", equalTo(true))
                .body("data.estimate.available", equalTo(true))
                .extract().path("data.requestId").toString();

            var row = jdbc.sql("""
                    SELECT l.lead_type::text AS lead_type,
                           l.listing_id,
                           l.intent_type::text AS intent_type,
                           l.source::text AS source,
                           l.stage::text AS stage,
                           l.contact_verified,
                           l.contact_email,
                           r.consent_granted,
                           r.consent_text,
                           r.estimate_min,
                           r.estimate_source,
                           r.selling_horizon,
                           r.contact_code_hash
                    FROM properia.property_valuation_requests r
                    JOIN properia.leads l ON l.id = r.lead_id
                    WHERE r.id = :id
                    """)
                .param("id", UUID.fromString(requestId))
                .query((rs, n) -> {
                    var m = new HashMap<String, Object>();
                    m.put("leadType", rs.getString("lead_type"));
                    m.put("listingId", rs.getObject("listing_id"));
                    m.put("intentType", rs.getString("intent_type"));
                    m.put("source", rs.getString("source"));
                    m.put("stage", rs.getString("stage"));
                    m.put("contactVerified", rs.getBoolean("contact_verified"));
                    m.put("contactEmail", rs.getString("contact_email"));
                    m.put("consentGranted", rs.getBoolean("consent_granted"));
                    m.put("consentText", rs.getString("consent_text"));
                    m.put("estimateMin", rs.getBigDecimal("estimate_min"));
                    m.put("estimateSource", rs.getString("estimate_source"));
                    m.put("sellingHorizon", rs.getString("selling_horizon"));
                    m.put("codeHash", rs.getString("contact_code_hash"));
                    return m;
                })
                .single();

            assertThat(row.get("leadType")).isEqualTo("owner");
            // O ponto central da V78: um lead sem imóvel.
            assertThat(row.get("listingId")).isNull();
            assertThat(row.get("intentType")).isEqualTo("valuation");
            assertThat(row.get("source")).isEqualTo("owner_landing");
            assertThat(row.get("stage")).isEqualTo("new");
            // Contacto ainda não provado — não deve consumir tempo comercial.
            assertThat(row.get("contactVerified")).isEqualTo(false);
            assertThat(row.get("consentGranted")).isEqualTo(true);
            assertThat(row.get("consentText")).isEqualTo(body.get("consentText"));
            assertThat(row.get("estimateSource")).isEqualTo("listings_parish");
            assertThat(row.get("sellingHorizon")).isEqualTo("3m");
            // O código é guardado em hash, nunca em claro.
            assertThat((String) row.get("codeHash")).hasSize(64);
        }

        @Test
        @DisplayName("guarda as entradas do cálculo para auditoria")
        void guardaEntradasDoCalculo() {
            var requestId = given().contentType(ContentType.JSON).body(validSubmission())
                .when().post(BASE + "/requests")
                .then().statusCode(201)
                .extract().path("data.requestId").toString();

            var inputs = jdbc.sql("""
                    SELECT estimate_inputs::text FROM properia.property_valuation_requests
                    WHERE id = :id
                    """)
                .param("id", UUID.fromString(requestId))
                .query(String.class).single();

            assertThat(inputs)
                .contains("basePricePerM2")
                .contains("factors")
                .contains("cascade")
                .contains("engineVersion");
        }

        @Test
        @DisplayName("sem consentimento não passa — consentimento por omissão não é consentimento")
        void exigeConsentimento() {
            var body = validSubmission();
            body.put("consentGranted", false);

            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("honeypot preenchido é bot")
        void rejeitaHoneypot() {
            var body = validSubmission();
            body.put("hp", "http://spam.example");

            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("preenchido em menos de 3 segundos é automação")
        void rejeitaPreenchimentoInstantaneo() {
            var body = validSubmission();
            body.put("formStartedAt", System.currentTimeMillis());

            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(422);
        }

        @Test
        @DisplayName("valida contactos e código postal")
        void validaContactos() {
            var semEmail = validSubmission();
            semEmail.put("contactEmail", "isto-nao-e-um-email");
            given().contentType(ContentType.JSON).body(semEmail)
                .when().post(BASE + "/requests")
                .then().statusCode(400);

            var telefoneInvalido = validSubmission();
            telefoneInvalido.put("contactPhone", "abc");
            given().contentType(ContentType.JSON).body(telefoneInvalido)
                .when().post(BASE + "/requests")
                .then().statusCode(400);

            var postalInvalido = validSubmission();
            postalInvalido.put("postalCode", "4100");
            given().contentType(ContentType.JSON).body(postalInvalido)
                .when().post(BASE + "/requests")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("horizonte de venda fora do domínio é rejeitado")
        void validaHorizonte() {
            var body = validSubmission();
            body.put("sellingHorizon", "amanha");

            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("sem comparáveis o pedido entra na mesma — o lead vale mais do que a estimativa")
        void aceitaPedidoSemEstimativa() {
            var body = validSubmission();
            body.put("municipality", MUNICIPIO_SEM_DADOS);
            body.put("parish", null);

            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(201)
                .body("data.estimate.available", equalTo(false))
                .body("data.requestId", notNullValue());
        }

        @Test
        @DisplayName("o mesmo email não pode submeter indefinidamente")
        void limitaSubmissoesPorEmail() {
            var email = "repetido." + UUID.randomUUID() + "@exemplo.pt";

            for (int i = 0; i < 5; i++) {
                var body = validSubmission();
                body.put("contactEmail", email);
                given().contentType(ContentType.JSON).body(body)
                    .when().post(BASE + "/requests")
                    .then().statusCode(201);
            }

            var body = validSubmission();
            body.put("contactEmail", email);
            given().contentType(ContentType.JSON).body(body)
                .when().post(BASE + "/requests")
                .then().statusCode(429);
        }
    }

    @Nested
    @DisplayName("POST /requests/{id}/verify")
    class VerifyContact {

        /** Substitui o código emitido por um conhecido — o real só existe no email. */
        private void forceCode(UUID requestId, String code) {
            jdbc.sql("""
                    UPDATE properia.property_valuation_requests
                    SET contact_code_hash = :hash,
                        contact_code_expires_at = now() + interval '10 minutes',
                        contact_code_failed_attempts = 0
                    WHERE id = :id
                    """)
                .param("hash", CreateOwnerValuationRequestUseCase.hashOtp(code))
                .param("id", requestId)
                .update();
        }

        private UUID submit() {
            return UUID.fromString(given().contentType(ContentType.JSON).body(validSubmission())
                .when().post(BASE + "/requests")
                .then().statusCode(201)
                .extract().path("data.requestId").toString());
        }

        @Test
        @DisplayName("código certo marca o lead como contacto verificado")
        void codigoCertoVerificaOLead() {
            var requestId = submit();
            forceCode(requestId, "123456");

            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(200)
                .body("data.verified", equalTo(true));

            var verified = jdbc.sql("""
                    SELECT l.contact_verified
                    FROM properia.leads l
                    JOIN properia.property_valuation_requests r ON r.lead_id = l.id
                    WHERE r.id = :id
                    """).param("id", requestId).query(Boolean.class).single();

            assertThat(verified).isTrue();
        }

        @Test
        @DisplayName("código errado falha e conta a tentativa")
        void codigoErradoContaTentativa() {
            var requestId = submit();
            forceCode(requestId, "123456");

            given().contentType(ContentType.JSON).body(Map.of("code", "999999"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(422);

            var attempts = jdbc.sql("""
                    SELECT contact_code_failed_attempts
                    FROM properia.property_valuation_requests WHERE id = :id
                    """).param("id", requestId).query(Integer.class).single();

            assertThat(attempts).isEqualTo(1);
        }

        @Test
        @DisplayName("cinco tentativas falhadas trancam o código")
        void trancaAposCincoTentativas() {
            var requestId = submit();
            forceCode(requestId, "123456");

            for (int i = 0; i < 5; i++) {
                given().contentType(ContentType.JSON).body(Map.of("code", "999999"))
                    .when().post(BASE + "/requests/" + requestId + "/verify")
                    .then().statusCode(422);
            }

            // Mesmo com o código CERTO: esgotadas as tentativas, exige um novo.
            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(403);
        }

        @Test
        @DisplayName("código expirado é recusado")
        void recusaCodigoExpirado() {
            var requestId = submit();
            jdbc.sql("""
                    UPDATE properia.property_valuation_requests
                    SET contact_code_hash = :hash,
                        contact_code_expires_at = now() - interval '1 minute'
                    WHERE id = :id
                    """)
                .param("hash", CreateOwnerValuationRequestUseCase.hashOtp("123456"))
                .param("id", requestId)
                .update();

            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(409);
        }

        @Test
        @DisplayName("verificar duas vezes é idempotente")
        void verificarDuasVezesEIdempotente() {
            var requestId = submit();
            forceCode(requestId, "123456");

            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(200);

            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(200)
                .body("data.verified", equalTo(true));
        }

        @Test
        @DisplayName("pedido inexistente devolve 404 e formato de código é validado")
        void pedidoInexistenteEFormato() {
            given().contentType(ContentType.JSON).body(Map.of("code", "123456"))
                .when().post(BASE + "/requests/" + UUID.randomUUID() + "/verify")
                .then().statusCode(404);

            var requestId = submit();
            given().contentType(ContentType.JSON).body(Map.of("code", "12"))
                .when().post(BASE + "/requests/" + requestId + "/verify")
                .then().statusCode(400);
        }

        @Test
        @DisplayName("reenvio imediato do código respeita o cooldown")
        void reenvioRespeitaCooldown() {
            var requestId = submit();

            given().contentType(ContentType.JSON)
                .when().post(BASE + "/requests/" + requestId + "/resend-code")
                .then().statusCode(409);
        }
    }

    @Nested
    @DisplayName("GET /report/{token}")
    class Report {

        @Test
        @DisplayName("o token do email abre o relatório")
        void tokenAbreORelatorio() {
            var token = given().contentType(ContentType.JSON).body(validSubmission())
                .when().post(BASE + "/requests")
                .then().statusCode(201)
                .extract().path("data.publicToken").toString();

            given()
                .when().get(BASE + "/report/" + token)
                .then().statusCode(200)
                .body("data.estimateMin", notNullValue())
                .body("data.confidence", notNullValue())
                .body("data.contactVerified", equalTo(false))
                .body("data.disclaimer", notNullValue());
        }

        @Test
        @DisplayName("token desconhecido devolve 404 — não serve para enumerar pedidos")
        void tokenDesconhecidoDa404() {
            given()
                .when().get(BASE + "/report/token-que-nao-existe")
                .then().statusCode(404);
        }
    }
}
