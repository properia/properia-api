package pt.properia.api.modules.advertiser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Regressão do fluxo de compra de créditos, que estava inoperante ponta a ponta:
 * 1) BillingService.createCreditCheckout lançava 501 em modo Stripe real;
 * 2) em modo fake, o redirect trazia ?checkout=fake&credits=<packCode> mas nada
 *    creditava o saldo (nem no backend, nem via webhook — não há webhook em fake);
 * 3) o frontend enviava {pack: ...} mas o backend só lia "packCode" — o pack
 *    escolhido pelo utilizador era sempre ignorado, caía no default "basic";
 * 4) o frontend esperava {checkoutUrl} da resposta, mas o backend devolvia {url}
 *    — window.location.href ficava "undefined".
 *
 * Este teste cobre o caminho novo: createCreditCheckout cria uma sessão "fake"
 * pendente, e /credits/confirm-fake resgata-a UMA só vez, atomicamente.
 */
@DisplayName("Compra de créditos (modo fake) — checkout → confirmação → saldo")
class AdvertiserCreditPurchaseIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID userId;
    private UUID otherAdvertiserId;
    private UUID otherUserId;

    @BeforeEach
    void setup() {
        advertiserId = UUID.randomUUID();
        userId = UUID.randomUUID();
        otherAdvertiserId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();

        for (var pair : Map.of(advertiserId, userId, otherAdvertiserId, otherUserId).entrySet()) {
            var adv = pair.getKey();
            var usr = pair.getValue();
            jdbc.sql("""
                    INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                    VALUES (:id, :email, 'Test Agent', 'agent', true, '{}'::jsonb, '{}'::jsonb)
                    """)
                .param("id", usr).param("email", usr + "@test.properia.pt").update();
            jdbc.sql("""
                    INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active, plan_code)
                    VALUES (:id, 'agency', 'Agência Teste Lda', true, 'starter')
                    """)
                .param("id", adv).update();
            jdbc.sql("""
                    INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                    VALUES (:adv, :usr, 'owner')
                    """)
                .param("adv", adv).param("usr", usr).update();
        }
    }

    private String sessionIdFromCheckoutUrl(String url) {
        var matcher = java.util.regex.Pattern.compile("session=([a-f0-9-]{36})").matcher(url);
        assertThatMatches(matcher);
        return matcher.group(1);
    }

    private void assertThatMatches(java.util.regex.Matcher matcher) {
        if (!matcher.find()) {
            throw new AssertionError("checkout url não contém um parâmetro session válido");
        }
    }

    @Test
    @DisplayName("Comprar o pack 'basic' credita exatamente 5 créditos após confirmação")
    void purchaseBasicPackCreditsFiveCredits() {
        var checkoutUrl = given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("packCode", "basic"))
            .when().post("/api/advertiser/billing/credits")
            .then().statusCode(200)
            .body("data.url", containsString("checkout=fake"))
            .body("data.url", containsString("credits=basic"))
            .extract().path("data.url").toString();

        var sessionId = sessionIdFromCheckoutUrl(checkoutUrl);

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("sessionId", sessionId))
            .when().post("/api/advertiser/billing/credits/confirm-fake")
            .then().statusCode(200)
            .body("data.balance", equalTo(5));

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/billing/credits")
            .then().statusCode(200)
            .body("data.balance", equalTo(5))
            .body("data.transactions[0].type", equalTo("purchase"))
            .body("data.transactions[0].amount", equalTo(5));
    }

    @Test
    @DisplayName("Confirmar a mesma sessão duas vezes só credita uma — idempotência (refresh da página)")
    void confirmingSameSessionTwiceOnlyCreditsOnce() {
        var checkoutUrl = given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("packCode", "standard"))
            .when().post("/api/advertiser/billing/credits")
            .then().statusCode(200)
            .extract().path("data.url").toString();
        var sessionId = sessionIdFromCheckoutUrl(checkoutUrl);

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("sessionId", sessionId))
            .when().post("/api/advertiser/billing/credits/confirm-fake")
            .then().statusCode(200)
            .body("data.balance", equalTo(15));

        // Segunda confirmação (ex.: utilizador dá refresh na página de retorno) — não duplica.
        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("sessionId", sessionId))
            .when().post("/api/advertiser/billing/credits/confirm-fake")
            .then().statusCode(200)
            .body("data.balance", equalTo(15));

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/billing/credits")
            .then().statusCode(200)
            .body("data.balance", equalTo(15))
            .body("data.transactions.size()", equalTo(1));
    }

    @Test
    @DisplayName("Pack de créditos inexistente é rejeitado com 400")
    void invalidPackCodeIsRejected() {
        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("packCode", "does_not_exist"))
            .when().post("/api/advertiser/billing/credits")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("Um anunciante não consegue resgatar a sessão de checkout de outro")
    void cannotClaimAnotherAdvertisersSession() {
        var checkoutUrl = given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("packCode", "professional"))
            .when().post("/api/advertiser/billing/credits")
            .then().statusCode(200)
            .extract().path("data.url").toString();
        var sessionId = sessionIdFromCheckoutUrl(checkoutUrl);

        // Outro anunciante tenta resgatar a sessão do primeiro — não credita nada ao intruso.
        given().cookie("properia_session", generateToken(otherUserId, "agent", true, otherAdvertiserId))
            .body(Map.of("sessionId", sessionId))
            .when().post("/api/advertiser/billing/credits/confirm-fake")
            .then().statusCode(200)
            .body("data.balance", equalTo(0));

        // O dono legítimo ainda consegue resgatar a sessão normalmente.
        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .body(Map.of("sessionId", sessionId))
            .when().post("/api/advertiser/billing/credits/confirm-fake")
            .then().statusCode(200)
            .body("data.balance", equalTo(40));
    }
}
