package pt.properia.api.modules.advertiser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Regressão do bug: GET /api/advertiser/plan recalculava o fim do trial com 14 dias
 * hardcoded, ignorando o trialEndsAt de 40 dias já gravado por BillingService.activateTrial().
 * Isto fazia downgrade prematuro (para "starter") de agências entre o dia 14 e o dia 40
 * do trial combinado.
 */
@DisplayName("Trial da agência — duração de 40 dias (não 14)")
class AdvertiserTrialDurationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private UUID advertiserId;
    private UUID userId;

    @BeforeEach
    void setup() {
        advertiserId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, 'Test Agent', 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """)
            .param("id", userId).param("email", userId + "@test.properia.pt").update();
        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active, plan_code)
                VALUES (:id, 'agency', 'Agência Teste Lda', true, 'business')
                """)
            .param("id", advertiserId).update();
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, 'owner')
                """)
            .param("adv", advertiserId).param("usr", userId).update();
    }

    private void setBillingMetadata(Instant activatedAt, Instant endsAt) throws Exception {
        var meta = endsAt != null
            ? Map.of("trialActivatedAt", activatedAt.toString(), "trialEndsAt", endsAt.toString())
            : Map.of("trialActivatedAt", activatedAt.toString());
        jdbc.sql("UPDATE properia.advertisers SET billing_metadata = :meta::jsonb WHERE id = :id")
            .param("meta", json.writeValueAsString(meta))
            .param("id", advertiserId)
            .update();
    }

    @Test
    @DisplayName("Trial ativado há 20 dias (de 40) continua ativo — antes do fix ficava expirado aos 14")
    void trialStillActiveAt20DaysOf40() throws Exception {
        var activatedAt = Instant.now().minus(20, ChronoUnit.DAYS);
        var endsAt = activatedAt.plus(40, ChronoUnit.DAYS); // como BillingService.activateTrial grava
        setBillingMetadata(activatedAt, endsAt);

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/plan")
            .then().statusCode(200)
            .body("data.trial.isActive", equalTo(true))
            .body("data.trial.daysRemaining", greaterThanOrEqualTo(19))
            .body("data.trial.daysRemaining", lessThanOrEqualTo(20))
            .body("data.effectivePlanCode", equalTo("business"));
    }

    @Test
    @DisplayName("Trial ativado há 39 dias (de 40) ainda ativo — só expira mesmo aos 40")
    void trialActiveJustBefore40Days() throws Exception {
        var activatedAt = Instant.now().minus(39, ChronoUnit.DAYS);
        var endsAt = activatedAt.plus(40, ChronoUnit.DAYS);
        setBillingMetadata(activatedAt, endsAt);

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/plan")
            .then().statusCode(200)
            .body("data.trial.isActive", equalTo(true));
    }

    @Test
    @DisplayName("Trial expira mesmo depois de passados os 40 dias combinados")
    void trialExpiresAfter40Days() throws Exception {
        var activatedAt = Instant.now().minus(41, ChronoUnit.DAYS);
        var endsAt = activatedAt.plus(40, ChronoUnit.DAYS); // já no passado
        setBillingMetadata(activatedAt, endsAt);

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/plan")
            .then().statusCode(200)
            .body("data.trial.isActive", equalTo(false))
            .body("data.effectivePlanCode", equalTo("starter"));
    }

    @Test
    @DisplayName("Trial legado sem trialEndsAt gravado usa fallback de 40 dias (não 14)")
    void legacyTrialWithoutEndsAtFallsBackTo40Days() throws Exception {
        var activatedAt = Instant.now().minus(20, ChronoUnit.DAYS);
        setBillingMetadata(activatedAt, null); // sem trialEndsAt — simula metadata antiga

        given().cookie("properia_session", generateToken(userId, "agent", true, advertiserId))
            .when().get("/api/advertiser/plan")
            .then().statusCode(200)
            .body("data.trial.isActive", equalTo(true))
            .body("data.effectivePlanCode", equalTo("business"));
    }
}
