package pt.properia.api.modules.signatures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import pt.properia.api.shared.IntegrationTestBase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;

/**
 * Fluxo end-to-end da feature de Documentos & Assinaturas (DB real via Testcontainers).
 * Cobre: criação, validações de upload, deteção de campos de modelo, cerimónia de
 * assinatura (OTP), assinatura multi-parte, selagem, verificação por hash e anulação.
 */
@DisplayName("Documentos & assinaturas — fluxo completo")
class DocumentSignatureFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcClient jdbc;

    private UUID advertiserId;
    private UUID userId;

    private static final String OTP = "123456";

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
                INSERT INTO properia.advertisers (id, advertiser_type, legal_name, tax_number, license_number, is_active)
                VALUES (:id, 'agency', 'Imobiliária Teste Lda', '500123456', 'AMI-9999', true)
                """)
            .param("id", advertiserId).update();
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
                VALUES (:adv, :usr, 'owner')
                """)
            .param("adv", advertiserId).param("usr", userId).update();
    }

    // ── Ficha de visita: criar → enviar → assinar → verificar ────────────────────

    @Test
    @DisplayName("Ficha de visita: fluxo completo de assinatura com OTP + verificação por hash")
    void visitFormFullSignFlow() {
        var docId = createDocument(Map.of(
            "documentType", "visit_form",
            "title", "Ficha de visita — Teste",
            "signers", List.of(signer("client", "Cliente", "Ana", "ana@test.pt")),
            "payload", Map.of("clientName", "Ana Conceição")));

        // Estado inicial: rascunho, 1 signatário, 0 assinados
        asAgent(advertiserId).get("/api/advertiser/signatures/" + docId).then()
            .statusCode(200)
            .body("data.item.status", equalTo("draft"))
            .body("data.item.signerCount", equalTo(1))
            .body("data.item.signedCount", equalTo(0));

        send(docId);
        String token = signerToken(docId, 1);
        forceOtp(docId);

        // Vista pública identifica a parte
        asGuest().get("/api/public/signatures/" + token).then()
            .statusCode(200)
            .body("data.roleLabel", equalTo("Cliente"))
            .body("data.allSigned", equalTo(false));

        // Assinar com OTP correto → sela o documento
        String hash = sign(token, OTP).path("data.documentHash");
        org.junit.jupiter.api.Assertions.assertNotNull(hash, "Documento com 1 signatário deve selar ao assinar");

        // Verificação por hash confirma autenticidade
        asGuest().get("/api/public/signatures/verify/" + hash).then()
            .statusCode(200)
            .body("data.valid", equalTo(true))
            .body("data.signerName", equalTo("Ana"));

        asAgent(advertiserId).get("/api/advertiser/signatures/" + docId).then()
            .body("data.item.status", equalTo("signed"))
            .body("data.item.signedCount", equalTo(1));
    }

    // ── Validações de upload ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Upload rejeita ficheiro que não é PDF")
    void uploadRejectsNonPdf() {
        String notPdf = Base64.getEncoder().encodeToString("isto não é um pdf".getBytes(StandardCharsets.UTF_8));
        asAgent(advertiserId).body(Map.of(
                "documentType", "upload",
                "title", "Doc",
                "signers", List.of(signer("party1", "Parte", "Zé", "ze@test.pt")),
                "payload", Map.of(),
                "uploadedPdf", notPdf))
            .post("/api/advertiser/signatures")
            .then().statusCode(422);
    }

    @Test
    @DisplayName("Modelo rejeita PDF sem campos prenchíveis (AcroForm)")
    void templateRejectsPdfWithoutFields() {
        asAgent(advertiserId).body(Map.of("name", "Sem campos", "pdfBase64", plainPdfBase64()))
            .post("/api/advertiser/signature-templates")
            .then().statusCode(422).body("error.code", equalTo("NO_FORM_FIELDS"));
    }

    @Test
    @DisplayName("Modelo prenchível: deteta campos e cria documento preenchido")
    void templateDetectAndFill() {
        String templateId = asAgent(advertiserId)
            .body(Map.of("name", "CPCV teste", "pdfBase64", fillablePdfBase64()))
            .post("/api/advertiser/signature-templates")
            .then().statusCode(200)
            .body("data.fields.name", hasItem("nome_comprador"))
            .extract().path("data.id");

        var docId = createDocument(Map.of(
            "documentType", "template",
            "title", "CPCV — Teste",
            "templateId", templateId,
            "fieldValues", Map.of("nome_comprador", "Ana Conceição"),
            "signers", List.of(signer("buyer", "Comprador", "Ana", "ana@test.pt")),
            "payload", Map.of()));

        asAgent(advertiserId).get("/api/advertiser/signatures/" + docId).then()
            .statusCode(200).body("data.item.status", equalTo("draft"));
    }

    // ── Assinatura multi-parte ───────────────────────────────────────────────────

    @Test
    @DisplayName("Multi-parte: sela só quando TODAS as partes assinam")
    void multiPartySealsWhenAllSign() {
        var docId = createDocument(Map.of(
            "documentType", "upload",
            "title", "CPCV upload",
            "uploadedPdf", plainPdfBase64(),
            "payload", Map.of(),
            "signers", List.of(
                signer("seller", "Vendedor", "José", "jose@test.pt"),
                signer("buyer", "Comprador", "Ana", "ana@test.pt"))));

        send(docId);
        forceOtp(docId);
        var tokens = allSignerTokens(docId);

        // 1ª parte assina → parcial, ainda não selado
        sign(tokens.get(0), OTP).then()
            .body("data.allSigned", equalTo(false))
            .body("data.remaining", equalTo(1))
            .body("data.documentHash", nullValue());

        asAgent(advertiserId).get("/api/advertiser/signatures/" + docId).then()
            .body("data.item.status", equalTo("partially_signed"))
            .body("data.item.signedCount", equalTo(1));

        // 2ª parte assina → selado
        sign(tokens.get(1), OTP).then()
            .body("data.allSigned", equalTo(true))
            .body("data.documentHash", notNullValue());

        asAgent(advertiserId).get("/api/advertiser/signatures/" + docId).then()
            .body("data.item.status", equalTo("signed"))
            .body("data.item.signedCount", equalTo(2));
    }

    // ── Regras de OTP e de negócio ───────────────────────────────────────────────

    @Test
    @DisplayName("OTP incorreto é rejeitado (422)")
    void wrongOtpRejected() {
        var docId = createDocument(Map.of(
            "documentType", "visit_form", "title", "Ficha",
            "signers", List.of(signer("client", "Cliente", "Ana", "ana@test.pt")),
            "payload", Map.of()));
        send(docId);
        forceOtp(docId);
        String token = signerToken(docId, 1);

        sign(token, "000000").then().statusCode(422).body("error.code", equalTo("OTP_INVALID"));
    }

    @Test
    @DisplayName("Documento assinado é imutável — não pode ser anulado (409)")
    void cannotCancelSignedDocument() {
        var docId = createDocument(Map.of(
            "documentType", "visit_form", "title", "Ficha",
            "signers", List.of(signer("client", "Cliente", "Ana", "ana@test.pt")),
            "payload", Map.of()));
        send(docId);
        forceOtp(docId);
        sign(signerToken(docId, 1), OTP);

        asAgent(advertiserId).post("/api/advertiser/signatures/" + docId + "/cancel")
            .then().statusCode(409).body("error.code", equalTo("CANNOT_CANCEL"));
    }

    @Test
    @DisplayName("Documento por assinar pode ser anulado; o link deixa de funcionar")
    void cancelUnsignedThenSignFails() {
        var docId = createDocument(Map.of(
            "documentType", "visit_form", "title", "Ficha",
            "signers", List.of(signer("client", "Cliente", "Ana", "ana@test.pt")),
            "payload", Map.of()));
        send(docId);
        forceOtp(docId);
        String token = signerToken(docId, 1);

        asAgent(advertiserId).post("/api/advertiser/signatures/" + docId + "/cancel")
            .then().statusCode(200).body("data.cancelled", equalTo(true));

        // Já não é possível assinar um documento anulado
        sign(token, OTP).then().statusCode(410).body("error.code", equalTo("CANCELLED"));
    }

    @Test
    @DisplayName("Verificação por hash inexistente devolve valid=false")
    void verifyUnknownHash() {
        asGuest().get("/api/public/signatures/verify/" + "0".repeat(64)).then()
            .statusCode(200).body("data.valid", equalTo(false));
    }

    @Test
    @DisplayName("Anunciante de outra agência não vê o documento (isolamento)")
    void tenantIsolation() {
        var docId = createDocument(Map.of(
            "documentType", "visit_form", "title", "Ficha",
            "signers", List.of(signer("client", "Cliente", "Ana", "ana@test.pt")),
            "payload", Map.of()));

        asAgent(UUID.randomUUID()).get("/api/advertiser/signatures/" + docId)
            .then().statusCode(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Map<String, Object> signer(String role, String label, String name, String email) {
        return Map.of("role", role, "roleLabel", label, "name", name, "email", email);
    }

    private String createDocument(Map<String, Object> body) {
        return asAgent(advertiserId).body(body).post("/api/advertiser/signatures")
            .then().statusCode(200).extract().path("data.id");
    }

    private void send(String docId) {
        asAgent(advertiserId).post("/api/advertiser/signatures/" + docId + "/send")
            .then().statusCode(200);
    }

    private io.restassured.response.Response sign(String token, String otp) {
        return asGuest().body(Map.of("otp", otp, "signatureImage", ""))
            .post("/api/public/signatures/" + token + "/sign");
    }

    /** Força um OTP conhecido em todos os signatários do documento (o real vai por email). */
    private void forceOtp(String docId) {
        jdbc.sql("""
                UPDATE properia.document_signers
                SET otp_code_hash = :h, otp_expires_at = now() + interval '15 minutes', otp_attempts = 0
                WHERE document_id = :d
                """)
            .param("h", sha256Hex(OTP)).param("d", UUID.fromString(docId)).update();
    }

    private String signerToken(String docId, int order) {
        return jdbc.sql("SELECT sign_token FROM properia.document_signers WHERE document_id = :d AND sign_order = :o")
            .param("d", UUID.fromString(docId)).param("o", order).query(String.class).single();
    }

    private List<String> allSignerTokens(String docId) {
        return jdbc.sql("SELECT sign_token FROM properia.document_signers WHERE document_id = :d ORDER BY sign_order")
            .param("d", UUID.fromString(docId)).query(String.class).list();
    }

    private static String sha256Hex(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String plainPdfBase64() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fillablePdfBase64() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            var res = new org.apache.pdfbox.pdmodel.PDResources();
            res.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA);
            form.setDefaultResources(res);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            var field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
            field.setPartialName("nome_comprador");
            var widget = field.getWidgets().get(0);
            widget.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(50, 700, 200, 20));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            form.getFields().add(field);
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
