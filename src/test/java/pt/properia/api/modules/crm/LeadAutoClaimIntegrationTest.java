package pt.properia.api.modules.crm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre duas decisões de produto sobre a relação entre angariador do imóvel
 * (listings.owner_user_id) e responsável pelo lead (leads.assigned_to), que são
 * campos INDEPENDENTES — atribuir um imóvel não atribui os leads que ele gera:
 *
 *  1. RBAC de escrita: além de owner/admin e do próprio responsável, o angariador
 *     do imóvel também pode mexer nos leads gerados nesse imóvel; e um lead sem
 *     responsável (fila geral / pool de SDR) está aberto a qualquer membro.
 *
 *  2. Auto-claim ("first to claim"): quem, sendo sales, muda a etapa de um lead da
 *     fila geral fica automaticamente responsável por ele — com evento de auditoria.
 *     owner/admin fazem triagem sem tomar posse.
 */
@DisplayName("Leads — RBAC de angariador e auto-claim da fila geral")
class LeadAutoClaimIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID ownerUserId;     // membership owner
    private UUID salesA;          // sales — angariador do imóvel
    private UUID salesB;          // sales — sem relação com o imóvel
    private UUID listingId;

    @BeforeEach
    void setup() {
        advertiserId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
        salesA = UUID.randomUUID();
        salesB = UUID.randomUUID();
        listingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, is_active, plan_code)
                VALUES (:id, 'agency', 'Agência Teste Lda', true, 'business')
                """).param("id", advertiserId).update();

        createMember(ownerUserId, "owner");
        createMember(salesA, "sales");
        createMember(salesB, "sales");

        // Imóvel angariado pelo salesA.
        jdbc.sql("""
                INSERT INTO properia.listings
                  (id, public_id, advertiser_id, owner_user_id, business_type, property_type, title, title_normalized)
                VALUES (:id, :pub, :adv, :owner, 'sale', 'apartment', 'T2 no Porto', 't2 no porto')
                """)
            .param("id", listingId).param("pub", "L-" + listingId.toString().substring(0, 8))
            .param("adv", advertiserId).param("owner", salesA)
            .update();
    }

    private void createMember(UUID userId, String membershipRole) {
        jdbc.sql("""
                INSERT INTO properia.app_users (id, email, full_name, role, is_active, preferences, consents)
                VALUES (:id, :email, 'Test User', 'agent', true, '{}'::jsonb, '{}'::jsonb)
                """)
            .param("id", userId).param("email", userId + "@test.properia.pt").update();
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, CAST(:role AS properia.advertiser_membership_role))
                """)
            .param("adv", advertiserId).param("usr", userId).param("role", membershipRole).update();
    }

    /** Cria um lead no imóvel do salesA. assignedTo null = está na fila geral. */
    private UUID createLead(UUID assignedTo) {
        var leadId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO properia.leads
                  (id, listing_id, advertiser_id, source, stage, intent_type, contact_name, assigned_to)
                VALUES (:id, :lst, :adv, 'contact_request', 'new', 'buy', 'Comprador Teste', :assigned)
                """)
            .param("id", leadId).param("lst", listingId).param("adv", advertiserId)
            .param("assigned", assignedTo)
            .update();
        return leadId;
    }

    private UUID assignedToOf(UUID leadId) {
        return jdbc.sql("SELECT assigned_to FROM properia.leads WHERE id = :id")
            .param("id", leadId).query(UUID.class).optional().orElse(null);
    }

    private String stageOf(UUID leadId) {
        return jdbc.sql("SELECT stage::text FROM properia.leads WHERE id = :id")
            .param("id", leadId).query(String.class).single();
    }

    private long autoClaimAuditCount(UUID leadId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM properia.crm_audit_events
                WHERE lead_id = :id AND action = 'lead_auto_claimed'
                """).param("id", leadId).query(Long.class).single();
    }

    private io.restassured.response.Response patchStage(UUID actor, UUID leadId, String stage) {
        return withAuth(generateToken(actor, "agent", true, advertiserId))
            .body(Map.of("stage", stage))
            .patch("/api/advertiser/leads/" + leadId + "/stage");
    }

    // ── Auto-claim ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sales que muda a etapa de um lead da fila geral fica responsável por ele")
    void salesAutoClaimsUnassignedLead() {
        var leadId = createLead(null);

        patchStage(salesB, leadId, "contacted").then().statusCode(200);

        assertThat(assignedToOf(leadId)).isEqualTo(salesB);
        assertThat(stageOf(leadId)).isEqualTo("contacted");
    }

    @Test
    @DisplayName("o auto-claim deixa registo na trilha de auditoria do CRM")
    void autoClaimWritesAuditEvent() {
        var leadId = createLead(null);

        patchStage(salesB, leadId, "contacted").then().statusCode(200);

        assertThat(autoClaimAuditCount(leadId)).isEqualTo(1);
    }

    @Test
    @DisplayName("owner faz triagem sem tomar posse — o lead continua na fila geral")
    void ownerDoesNotAutoClaim() {
        var leadId = createLead(null);

        patchStage(ownerUserId, leadId, "contacted").then().statusCode(200);

        assertThat(assignedToOf(leadId)).isNull();
        assertThat(stageOf(leadId)).isEqualTo("contacted");
        assertThat(autoClaimAuditCount(leadId)).isZero();
    }

    @Test
    @DisplayName("lead já atribuído não muda de responsável ao mudar de etapa")
    void alreadyAssignedLeadIsNotReclaimed() {
        var leadId = createLead(salesA);

        patchStage(salesA, leadId, "contacted").then().statusCode(200);

        assertThat(assignedToOf(leadId)).isEqualTo(salesA);
        assertThat(autoClaimAuditCount(leadId)).isZero();
    }

    @Test
    @DisplayName("atribuição explícita no pedido manda sobre o auto-claim")
    void explicitAssignmentWinsOverAutoClaim() {
        var leadId = createLead(null);

        withAuth(generateToken(salesB, "agent", true, advertiserId))
            .body(Map.of("stage", "contacted", "assignedTo", salesA.toString()))
            .patch("/api/advertiser/leads/" + leadId + "/stage")
            .then().statusCode(200);

        assertThat(assignedToOf(leadId)).isEqualTo(salesA);
    }

    // ── RBAC ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("o angariador do imóvel pode mexer num lead atribuído a outro consultor")
    void listingOwnerCanModifyLeadAssignedToSomeoneElse() {
        var leadId = createLead(salesB);   // atribuído ao B; imóvel é angariação do A

        patchStage(salesA, leadId, "contacted").then().statusCode(200);

        assertThat(stageOf(leadId)).isEqualTo("contacted");
        // Não rouba o lead: continua responsabilidade de quem o tinha.
        assertThat(assignedToOf(leadId)).isEqualTo(salesB);
    }

    @Test
    @DisplayName("sales sem relação com o lead nem com o imóvel recebe 403")
    void unrelatedSalesIsForbidden() {
        var leadId = createLead(ownerUserId);  // atribuído ao owner; imóvel angariado pelo A

        patchStage(salesB, leadId, "contacted").then().statusCode(403);

        assertThat(stageOf(leadId)).isEqualTo("new");
    }

    @Test
    @DisplayName("qualquer membro pode pegar num lead da fila geral (não é 403)")
    void unassignedLeadIsOpenToAnyMember() {
        var leadId = createLead(null);

        patchStage(salesB, leadId, "qualified").then().statusCode(200);

        assertThat(stageOf(leadId)).isEqualTo("qualified");
    }
}
