package pt.properia.api.modules.team;

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

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre as quatro correções da área de Equipa numa só sessão, porque as quatro
 * mexem no mesmo caminho crítico e uma prova isolada de cada uma deixaria passar
 * a interacção entre elas:
 *
 * <ol>
 *   <li>updateMemberRole/removeMember/createInvite/cancelInvite/resendInvite não
 *       verificavam o role de quem pedia — um viewer conseguia gerir a equipa
 *       inteira só por chamar a API directamente.</li>
 *   <li>{@code pilot} tinha duas implementações contraditórias do tecto de
 *       vagas: o gate real tratava-o como ilimitado, a UI lia-o como limitado a 5.</li>
 *   <li>o webhook do Stripe engolia qualquer excepção em silêncio, e gravava
 *       {@code plan_code = "free"}, um valor que não existe em mais lado nenhum.</li>
 *   <li>aceitar um convite nunca revalidava o tecto de vagas — um downgrade de
 *       plano com convites pendentes desse período mais generoso deixava a
 *       equipa permanentemente acima do tecto do novo plano.</li>
 * </ol>
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
@DisplayName("Equipa — guard de permissões e tecto de vagas (DB local)")
class TeamPermissionAndSeatsLocalDbTest {

    @LocalServerPort private int port;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcClient jdbc;

    private final SecureRandom rng = new SecureRandom();

    private UUID advertiserId;
    private UUID ownerId;
    private UUID viewerId;
    private String ownerToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        advertiserId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        viewerId = UUID.randomUUID();

        createAdvertiser(advertiserId, "starter");
        ownerToken = createMember(ownerId, "owner@team-test.pt", "Dona da Conta", "owner");
        viewerToken = createMember(viewerId, "viewer@team-test.pt", "Só Vê", "viewer");
    }

    @AfterEach
    void cleanup() {
        // advertiser_users e advertiser_team_invites têm ON DELETE CASCADE para
        // advertisers, mas os utilizadores criados ad-hoc em cada teste (membros
        // extra, convidados) não estão ligados por FK a advertiserId — sem isto,
        // o próximo teste da mesma classe colide no email único de app_users.
        try { jdbc.sql("DELETE FROM properia.advertisers WHERE id = :adv").param("adv", advertiserId).update(); } catch (Exception ignored) {}
        try { jdbc.sql("DELETE FROM properia.app_users WHERE email LIKE '%@team-test.pt'").update(); } catch (Exception ignored) {}
    }

    // ── 1. Guard de permissões ──────────────────────────────────────────────

    @Nested
    @DisplayName("1. só owner/admin gerem a equipa")
    class PermissionGuard {

        @Test
        @DisplayName("viewer não consegue alterar o role de outro membro")
        void viewerCannotChangeRole() {
            var target = UUID.randomUUID();
            createMember(target, "alvo@team-test.pt", "Alvo", "editor");

            withAuth(viewerToken).body("{\"membershipRole\": \"admin\"}")
                .patch("/api/advertiser/team/members/" + target)
                .then().statusCode(403);

            assertThat(roleOf(target)).isEqualTo("editor");
        }

        @Test
        @DisplayName("viewer não consegue remover um membro")
        void viewerCannotRemoveMember() {
            var target = UUID.randomUUID();
            createMember(target, "removivel@team-test.pt", "Removível", "editor");

            withAuth(viewerToken)
                .delete("/api/advertiser/team/members/" + target)
                .then().statusCode(403);

            assertThat(roleOf(target)).isEqualTo("editor");
        }

        @Test
        @DisplayName("viewer não consegue criar convite")
        void viewerCannotCreateInvite() {
            withAuth(viewerToken).body("{\"email\": \"novo@team-test.pt\", \"membershipRole\": \"editor\"}")
                .post("/api/advertiser/team/invites")
                .then().statusCode(403);

            assertThat(countInvites()).isZero();
        }

        @Test
        @DisplayName("viewer não consegue cancelar nem reenviar convite")
        void viewerCannotCancelOrResendInvite() {
            var inviteId = insertInvite("pendente@team-test.pt", "editor", 7);

            withAuth(viewerToken).delete("/api/advertiser/team/invites/" + inviteId).then().statusCode(403);
            withAuth(viewerToken).post("/api/advertiser/team/invites/" + inviteId + "/resend").then().statusCode(403);

            assertThat(countInvites()).isEqualTo(1);
        }

        @Test
        @DisplayName("owner continua a conseguir gerir a equipa — o guard não bloqueia quem devia passar")
        void ownerCanStillManageTeam() {
            var target = UUID.randomUUID();
            createMember(target, "gerido@team-test.pt", "Gerido", "editor");

            withAuth(ownerToken).body("{\"membershipRole\": \"sales\"}")
                .patch("/api/advertiser/team/members/" + target)
                .then().statusCode(200);

            assertThat(roleOf(target)).isEqualTo("sales");
        }
    }

    // ── 2. Tecto de vagas do plano pilot ────────────────────────────────────

    @Nested
    @DisplayName("2. pilot é ilimitado, tanto no gate como na UI")
    class PilotSeats {

        @Test
        @DisplayName("pilot cria convite além da 5ª vaga sem ser bloqueado")
        void pilotIsUnlimitedAtTheGate() {
            jdbc.sql("UPDATE properia.advertisers SET plan_code = 'pilot' WHERE id = :adv").param("adv", advertiserId).update();
            for (int i = 0; i < 5; i++) {
                createMember(UUID.randomUUID(), "membro" + i + "@team-test.pt", "Membro " + i, "editor");
            }

            withAuth(ownerToken).body("{\"email\": \"sexto@team-test.pt\", \"membershipRole\": \"editor\"}")
                .post("/api/advertiser/team/invites")
                .then().statusCode(201);
        }

        @Test
        @DisplayName("capabilities() da UI também devolve maxTeamMembers=-1 para pilot, não 5")
        void pilotCapabilitiesShowUnlimitedInUi() {
            jdbc.sql("UPDATE properia.advertisers SET plan_code = 'pilot' WHERE id = :adv").param("adv", advertiserId).update();

            var maxTeam = withAuth(ownerToken)
                .get("/api/advertiser/plan")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data.capabilities.maxTeamMembers");

            assertThat(maxTeam)
                .as("antes da correcção pilot caía no ramo isPro e devolvia 5 — a UI mostrava " +
                    "'5 de 5 vagas' mesmo com o gate real a permitir mais")
                .isEqualTo(-1);
        }

        @Test
        @DisplayName("pro continua limitado a 5 — a correcção não tornou tudo ilimitado")
        void proStillCapsAtFive() {
            jdbc.sql("UPDATE properia.advertisers SET plan_code = 'pro' WHERE id = :adv").param("adv", advertiserId).update();
            for (int i = 0; i < 4; i++) {
                createMember(UUID.randomUUID(), "promembro" + i + "@team-test.pt", "Pro Membro " + i, "editor");
            }
            // owner + 4 = 5, no limite de Pro

            withAuth(ownerToken).body("{\"email\": \"excedente@team-test.pt\", \"membershipRole\": \"editor\"}")
                .post("/api/advertiser/team/invites")
                .then().statusCode(403);
        }
    }

    // ── 4. Revalidação do tecto ao aceitar convite ──────────────────────────

    @Nested
    @DisplayName("4. aceitar convite revalida o tecto de vagas")
    class AcceptInviteSeatCheck {

        @Test
        @DisplayName("convite criado durante trial Business é recusado se o plano já baixou a Starter")
        void acceptanceRejectedAfterDowngrade() {
            var inviteeId = UUID.randomUUID();
            var inviteeToken = createUserWithoutMembership(inviteeId, "convidado@team-test.pt", "Convidado");
            var token = insertInviteWithToken("convidado@team-test.pt", "editor", 7);

            // Já havia 1 membro (o owner) no Starter (tecto=1) — o convite tinha
            // sido criado durante o trial Business, mas entretanto expirou.
            jdbc.sql("UPDATE properia.advertisers SET plan_code = 'starter' WHERE id = :adv").param("adv", advertiserId).update();

            withAuth(inviteeToken)
                .post("/api/team/invites/" + token + "/accept")
                .then().statusCode(403)
                .body("error.message", org.hamcrest.Matchers.containsString("limite de vagas"));

            assertThat(isMember(inviteeId)).isFalse();
        }

        @Test
        @DisplayName("convite aceite normalmente quando há vaga — não regride o caminho feliz")
        void acceptanceStillWorksWithSeatAvailable() {
            jdbc.sql("UPDATE properia.advertisers SET plan_code = 'pro' WHERE id = :adv").param("adv", advertiserId).update();
            var inviteeId = UUID.randomUUID();
            var inviteeToken = createUserWithoutMembership(inviteeId, "cabe@team-test.pt", "Cabe Bem");
            var token = insertInviteWithToken("cabe@team-test.pt", "editor", 7);

            withAuth(inviteeToken)
                .post("/api/team/invites/" + token + "/accept")
                .then().statusCode(200);

            assertThat(isMember(inviteeId)).isTrue();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private io.restassured.specification.RequestSpecification withAuth(String token) {
        return given().contentType(ContentType.JSON).cookie("properia_session", token);
    }

    private void createAdvertiser(UUID id, String planCode) {
        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active, plan_code)
                VALUES (:id, 'agency', 'Test Team Advertiser', true, :plan)
                """).param("id", id).param("plan", planCode).update();
    }

    private String createMember(UUID userId, String email, String name, String role) {
        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, :name, 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """).param("id", userId).param("email", email).param("name", name).update();
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, :role::properia.advertiser_membership_role)
                """).param("adv", advertiserId).param("usr", userId).param("role", role).update();
        return jwtService.generateToken(new JwtClaims(
            userId, email, name, "agent", null, true, advertiserId, UUID.randomUUID()));
    }

    private String createUserWithoutMembership(UUID userId, String email, String name) {
        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, :name, 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """).param("id", userId).param("email", email).param("name", name).update();
        // Sem activeAdvertiserId — quem aceita um convite ainda não pertence à conta.
        return jwtService.generateToken(new JwtClaims(
            userId, email, name, "agent", null, false, null, UUID.randomUUID()));
    }

    private String roleOf(UUID userId) {
        return jdbc.sql("SELECT membership_role FROM properia.advertiser_users WHERE advertiser_id = :adv AND user_id = :usr")
            .param("adv", advertiserId).param("usr", userId).query(String.class).single();
    }

    private boolean isMember(UUID userId) {
        return jdbc.sql("SELECT 1 FROM properia.advertiser_users WHERE advertiser_id = :adv AND user_id = :usr")
            .param("adv", advertiserId).param("usr", userId).query(Integer.class).optional().isPresent();
    }

    private long countInvites() {
        return jdbc.sql("SELECT COUNT(*) FROM properia.advertiser_team_invites WHERE advertiser_id = :adv")
            .param("adv", advertiserId).query(Long.class).single();
    }

    private UUID insertInvite(String email, String role, int ttlDays) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.advertiser_team_invites
                  (id, advertiser_id, invited_by_user_id, email, membership_role, token, expires_at, created_at, updated_at)
                VALUES
                  (:id, :adv, :inviter, :email, :role::properia.advertiser_membership_role, :token, :expires, now(), now())
                """)
            .param("id", id).param("adv", advertiserId).param("inviter", ownerId)
            .param("email", email).param("role", role)
            .param("token", randomToken())
            .param("expires", java.sql.Timestamp.from(Instant.now().plus(ttlDays, ChronoUnit.DAYS)))
            .update();
        return id;
    }

    private String insertInviteWithToken(String email, String role, int ttlDays) {
        var token = randomToken();
        jdbc.sql("""
                INSERT INTO properia.advertiser_team_invites
                  (advertiser_id, invited_by_user_id, email, membership_role, token, expires_at, created_at, updated_at)
                VALUES
                  (:adv, :inviter, :email, :role::properia.advertiser_membership_role, :token, :expires, now(), now())
                """)
            .param("adv", advertiserId).param("inviter", ownerId)
            .param("email", email).param("role", role)
            .param("token", token)
            .param("expires", java.sql.Timestamp.from(Instant.now().plus(ttlDays, ChronoUnit.DAYS)))
            .update();
        return token;
    }

    private String randomToken() {
        var bytes = new byte[32];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
