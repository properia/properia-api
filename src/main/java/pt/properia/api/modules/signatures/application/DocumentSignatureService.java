package pt.properia.api.modules.signatures.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.modules.signatures.application.DocumentPdfService.SignatureSlot;
import pt.properia.api.modules.signatures.application.DocumentPdfService.SignatureStamp;
import pt.properia.api.shared.domain.DomainException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assinatura Eletrónica Simples (SES) com prova forte e MULTI-PARTE, self-hosted:
 * um documento (envelope) tem 1+ signatários (ex.: CPCV = vendedor + comprador). Cada
 * signatário recebe o seu link + OTP por email e assina de forma independente. O PDF
 * final só é selado (com hash SHA-256) quando TODOS assinam.
 */
@Service
public class DocumentSignatureService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int OTP_MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final DocumentPdfService pdfService;
    private final AuthEmailService email;
    private final ObjectMapper json;

    public DocumentSignatureService(JdbcClient jdbc, DocumentPdfService pdfService,
                                    AuthEmailService email, ObjectMapper json) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.email = email;
        this.json = json;
    }

    public record SignerInput(String role, String roleLabel, String name, String email) {}

    public record CreateRequest(
        String documentType, String title, String signerName, String signerEmail,
        UUID leadId, UUID visitId, Map<String, Object> payload, List<SignerInput> signers,
        String uploadedPdf, UUID templateId, Map<String, Object> fieldValues) {}

    public record SignatureDto(
        String id, String documentType, String title, String status,
        String signerName, String signerEmail, String documentHash,
        int signerCount, int signedCount, Instant createdAt, Instant signedAt) {}

    public record PublicView(
        String title, String signerName, String roleLabel, String status,
        String documentType, boolean thisSignerSigned, boolean allSigned,
        int signedCount, int signerCount, String agencyName) {}

    public record SignResult(String documentHash, Instant signedAt, boolean allSigned, int remaining) {}

    public record VerifyResult(boolean valid, String title, String signerName, Instant signedAt) {}

    public record SignerInfo(String roleLabel, String signerName, String signerEmail,
                             String status, String signToken, Instant signedAt) {}

    /** Detalhe para o agente: DTO + timeline + signatários (com o link de cada um). */
    public record SignatureDetail(SignatureDto item, List<Map<String, Object>> auditTrail,
                                  List<SignerInfo> signers) {}

    // ── Criar ────────────────────────────────────────────────────────────────────

    @Transactional
    public SignatureDto create(UUID advertiserId, UUID userId, CreateRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Título do documento em falta.", 422);
        }
        var signers = resolveSigners(req);
        if (signers.isEmpty()) {
            throw new DomainException("VALIDATION_ERROR", "Indica pelo menos um signatário (nome e email).", 422);
        }

        var payload = new LinkedHashMap<String, Object>();
        if (req.payload() != null) payload.putAll(req.payload());
        enrichWithAgency(advertiserId, payload);
        var type = req.documentType() == null || req.documentType().isBlank() ? "visit_form" : req.documentType();

        // PDF não assinado, por origem:
        //  - modelo da agência (AcroForm) preenchido com os dados do formulário;
        //  - PDF já preenchido carregado (upload);
        //  - gerado por template interno (ficha de visita).
        byte[] unsigned;
        if (req.templateId() != null) {
            type = "template";
            byte[] tpl = jdbc.sql("SELECT pdf_template FROM properia.document_templates WHERE id = :id AND advertiser_id = :adv")
                .param("id", req.templateId()).param("adv", advertiserId)
                .query((rs, n) -> rs.getBytes("pdf_template"))
                .optional()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Modelo não encontrado.", 404));
            var values = new LinkedHashMap<String, String>();
            if (req.fieldValues() != null) {
                req.fieldValues().forEach((k, v) -> values.put(k, v == null ? "" : v.toString()));
            }
            unsigned = pdfService.fillAndFlatten(tpl, values);
        } else if ("upload".equals(type)) {
            unsigned = decodePdf(req.uploadedPdf());
        } else {
            var slots = signers.stream().map(s -> new SignatureSlot(s.roleLabel(), null)).toList();
            unsigned = pdfService.buildMultiDocument(type, payload, slots);
        }

        var primary = signers.get(0);
        var id = jdbc.sql("""
                INSERT INTO properia.document_signatures
                  (advertiser_id, created_by_user_id, lead_id, visit_id, document_type, title,
                   status, payload, unsigned_pdf, sign_token, signer_name, signer_email, audit)
                VALUES (:adv, :user, :lead, :visit, :type, :title,
                   'draft', CAST(:payload AS jsonb), :unsigned, :token, :signerName, :signerEmail,
                   CAST(:audit AS jsonb))
                RETURNING id
                """)
            .param("adv", advertiserId)
            .param("user", userId)
            .param("lead", req.leadId())
            .param("visit", req.visitId())
            .param("type", type)
            .param("title", req.title())
            .param("payload", writeJson(payload))
            .param("unsigned", unsigned)
            .param("token", randomToken())       // token do envelope (legado — não usado para assinar)
            .param("signerName", primary.name())
            .param("signerEmail", primary.email())
            .param("audit", writeJson(List.of(auditEvent("created", null))))
            .query(UUID.class).single();

        int order = 1;
        for (var s : signers) {
            jdbc.sql("""
                    INSERT INTO properia.document_signers
                      (document_id, role, role_label, sign_order, signer_name, signer_email, sign_token)
                    VALUES (:doc, :role, :label, :ord, :name, :email, :token)
                    """)
                .param("doc", id)
                .param("role", s.role())
                .param("label", s.roleLabel())
                .param("ord", order++)
                .param("name", s.name())
                .param("email", s.email())
                .param("token", randomToken())
                .update();
        }

        return findDto(advertiserId, id);
    }

    private List<SignerInput> resolveSigners(CreateRequest req) {
        var out = new ArrayList<SignerInput>();
        if (req.signers() != null) {
            for (var s : req.signers()) {
                if (s == null || s.name() == null || s.name().isBlank()
                    || s.email() == null || s.email().isBlank()) continue;
                out.add(new SignerInput(
                    s.role() == null || s.role().isBlank() ? "client" : s.role(),
                    s.roleLabel() == null || s.roleLabel().isBlank() ? "Signatário" : s.roleLabel(),
                    s.name().trim(), s.email().trim()));
            }
        }
        // Retrocompatibilidade: sem lista, usa o par signerName/signerEmail.
        if (out.isEmpty() && req.signerName() != null && !req.signerName().isBlank()
            && req.signerEmail() != null && !req.signerEmail().isBlank()) {
            out.add(new SignerInput("client", "Cliente", req.signerName().trim(), req.signerEmail().trim()));
        }
        return out;
    }

    // ── Enviar (gera OTP + email por signatário pendente) ─────────────────────────

    @Transactional
    public void send(UUID advertiserId, UUID id) {
        var doc = jdbc.sql("""
                SELECT title, status FROM properia.document_signatures
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> Map.of("title", rs.getString("title"), "status", rs.getString("status")))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));

        if ("signed".equals(doc.get("status"))) {
            throw new DomainException("ALREADY_SIGNED", "Este documento já foi assinado.", 409);
        }
        if ("cancelled".equals(doc.get("status"))) {
            throw new DomainException("CANCELLED", "Este documento foi anulado — cria um novo.", 409);
        }

        var pending = jdbc.sql("""
                SELECT id, signer_name, signer_email, sign_token
                FROM properia.document_signers
                WHERE document_id = :doc AND status <> 'signed'
                ORDER BY sign_order
                """)
            .param("doc", id)
            .query((rs, n) -> Map.of(
                "id", rs.getObject("id", UUID.class),
                "name", rs.getString("signer_name"),
                "email", rs.getString("signer_email"),
                "token", rs.getString("sign_token")))
            .list();

        for (var s : pending) {
            String otp = generateOtp();
            jdbc.sql("""
                    UPDATE properia.document_signers
                    SET otp_code_hash = :otpHash, otp_expires_at = now() + interval '15 minutes',
                        otp_attempts = 0, updated_at = now()
                    WHERE id = :id
                    """)
                .param("otpHash", sha256Hex(otp)).param("id", s.get("id"))
                .update();
            email.sendSignatureRequest(
                (String) s.get("email"), (String) s.get("name"),
                (String) doc.get("title"), otp, (String) s.get("token"));
        }

        jdbc.sql("""
                UPDATE properia.document_signatures
                SET status = CASE WHEN status = 'draft' THEN 'sent' ELSE status END,
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("event", writeJson(auditEvent("sent", null)))
            .param("id", id).param("adv", advertiserId)
            .update();
    }

    // ── Vista pública + PDF (por token de signatário) ─────────────────────────────

    @Transactional
    public PublicView getPublicView(String token) {
        var view = jdbc.sql("""
                SELECT d.title, d.document_type, s.signer_name, s.role_label,
                       s.status AS signer_status,
                       d.payload->>'agencyName' AS agency_name,
                       (SELECT count(*) FROM properia.document_signers x WHERE x.document_id = d.id) AS signer_count,
                       (SELECT count(*) FROM properia.document_signers x WHERE x.document_id = d.id AND x.status = 'signed') AS signed_count,
                       CASE WHEN d.status NOT IN ('signed','cancelled') AND d.expires_at < now()
                            THEN 'expired' ELSE d.status END AS doc_status
                FROM properia.document_signers s
                JOIN properia.document_signatures d ON d.id = s.document_id
                WHERE s.sign_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> {
                int total = rs.getInt("signer_count");
                int signed = rs.getInt("signed_count");
                return new PublicView(
                    rs.getString("title"), rs.getString("signer_name"), rs.getString("role_label"),
                    rs.getString("doc_status"), rs.getString("document_type"),
                    "signed".equals(rs.getString("signer_status")),
                    signed >= total && total > 0,
                    signed, total, rs.getString("agency_name"));
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado ou link inválido.", 404));

        jdbc.sql("""
                UPDATE properia.document_signers
                SET status = CASE WHEN status = 'pending' THEN 'viewed' ELSE status END, updated_at = now()
                WHERE sign_token = :token
                """)
            .param("token", token).update();
        // Marca o envelope como 'viewed' se ainda estava só 'sent'.
        jdbc.sql("""
                UPDATE properia.document_signatures d
                SET status = CASE WHEN d.status = 'sent' THEN 'viewed' ELSE d.status END,
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                FROM properia.document_signers s
                WHERE s.sign_token = :token AND s.document_id = d.id
                """)
            .param("event", writeJson(auditEvent("viewed", null)))
            .param("token", token).update();

        return view;
    }

    public byte[] getPublicPdf(String token) {
        return jdbc.sql("""
                SELECT COALESCE(d.signed_pdf, d.unsigned_pdf) AS pdf
                FROM properia.document_signers s
                JOIN properia.document_signatures d ON d.id = s.document_id
                WHERE s.sign_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> rs.getBytes("pdf"))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));
    }

    public byte[] getAdvertiserPdf(UUID advertiserId, UUID id) {
        return jdbc.sql("""
                SELECT COALESCE(signed_pdf, unsigned_pdf) AS pdf
                FROM properia.document_signatures WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> rs.getBytes("pdf"))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));
    }

    // ── Assinar (um signatário) ───────────────────────────────────────────────────

    @Transactional
    public SignResult sign(String token, String otp, String signatureImageBase64,
                           String ip, String userAgent) {
        var row = jdbc.sql("""
                SELECT s.id AS signer_id, s.status AS signer_status, s.otp_code_hash,
                       s.otp_expires_at, s.otp_attempts, s.signer_name, s.role_label,
                       d.id AS doc_id, d.document_type, d.status AS doc_status
                FROM properia.document_signers s
                JOIN properia.document_signatures d ON d.id = s.document_id
                WHERE s.sign_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("signerId", rs.getObject("signer_id", UUID.class));
                m.put("signerStatus", rs.getString("signer_status"));
                m.put("otpHash", rs.getString("otp_code_hash"));
                m.put("otpExpires", rs.getTimestamp("otp_expires_at") == null ? null : rs.getTimestamp("otp_expires_at").toInstant());
                m.put("attempts", rs.getInt("otp_attempts"));
                m.put("signerName", rs.getString("signer_name"));
                m.put("roleLabel", rs.getString("role_label"));
                m.put("docId", rs.getObject("doc_id", UUID.class));
                m.put("documentType", rs.getString("document_type"));
                m.put("docStatus", rs.getString("doc_status"));
                return m;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));

        if ("cancelled".equals(row.get("docStatus"))) {
            throw new DomainException("CANCELLED", "Este documento foi anulado pela agência.", 410);
        }
        if ("signed".equals(row.get("signerStatus"))) {
            throw new DomainException("ALREADY_SIGNED", "Já assinaste este documento.", 409);
        }
        if (row.get("otpHash") == null) {
            throw new DomainException("NOT_SENT", "O documento ainda não foi enviado para assinatura.", 409);
        }
        var expires = (Instant) row.get("otpExpires");
        if (expires == null || expires.isBefore(Instant.now())) {
            throw new DomainException("OTP_EXPIRED", "O código expirou. Pede um novo código.", 410);
        }
        if ((int) row.get("attempts") >= OTP_MAX_ATTEMPTS) {
            throw new DomainException("OTP_LOCKED", "Demasiadas tentativas. Pede um novo código.", 429);
        }
        if (otp == null || !sha256Hex(otp.trim()).equals(row.get("otpHash"))) {
            jdbc.sql("UPDATE properia.document_signers SET otp_attempts = otp_attempts + 1 WHERE sign_token = :token")
                .param("token", token).update();
            throw new DomainException("OTP_INVALID", "Código incorreto.", 422);
        }

        var docId = (UUID) row.get("docId");
        byte[] signatureImage = decodeSignature(signatureImageBase64);

        // Guarda a assinatura deste signatário.
        jdbc.sql("""
                UPDATE properia.document_signers
                SET status = 'signed', signed_at = now(), signer_ip = :ip, signer_user_agent = :ua,
                    signature_image = :sig, otp_code_hash = NULL, updated_at = now()
                WHERE id = :id
                """)
            .param("ip", ip).param("ua", userAgent).param("sig", signatureImage)
            .param("id", row.get("signerId"))
            .update();

        jdbc.sql("""
                UPDATE properia.document_signatures
                SET audit = audit || CAST(:event AS jsonb), updated_at = now() WHERE id = :doc
                """)
            .param("event", writeJson(auditEvent("signed:" + row.get("roleLabel"), ip)))
            .param("doc", docId)
            .update();

        // Todos assinaram?
        var counts = jdbc.sql("""
                SELECT count(*) AS total, count(*) FILTER (WHERE status = 'signed') AS signed
                FROM properia.document_signers WHERE document_id = :doc
                """)
            .param("doc", docId)
            .query((rs, n) -> new int[]{rs.getInt("total"), rs.getInt("signed")})
            .single();
        int total = counts[0], signed = counts[1];

        if (signed >= total) {
            // Sela o PDF final com TODAS as assinaturas.
            Map<String, Object> payload = readJsonMap(loadPayload(docId));
            var slots = loadSignerSlots(docId);
            var docType = (String) row.get("documentType");
            // Só a ficha de visita é gerada por nós; modelos/uploads mantêm o PDF base
            // intacto e recebem uma página de assinaturas anexada.
            boolean generated = "visit_form".equals(docType) || "cpcv".equals(docType) || "cmi".equals(docType);
            byte[] finalPdf = generated
                ? pdfService.buildMultiDocument(docType, payload, slots)
                : pdfService.appendSignatures(loadUnsignedPdf(docId), payload, slots);
            String hash = sha256Hex(finalPdf);
            jdbc.sql("""
                    UPDATE properia.document_signatures
                    SET signed_pdf = :pdf, document_hash = :hash, status = 'signed',
                        signed_at = now(), retention_until = now() + interval '5 years', updated_at = now()
                    WHERE id = :doc
                    """)
                .param("pdf", finalPdf).param("hash", hash).param("doc", docId)
                .update();
            return new SignResult(hash, Instant.now(), true, 0);
        } else {
            jdbc.sql("""
                    UPDATE properia.document_signatures
                    SET status = 'partially_signed', updated_at = now() WHERE id = :doc
                    """)
                .param("doc", docId).update();
            return new SignResult(null, Instant.now(), false, total - signed);
        }
    }

    private String loadPayload(UUID docId) {
        return jdbc.sql("SELECT payload::text FROM properia.document_signatures WHERE id = :doc")
            .param("doc", docId).query(String.class).single();
    }

    private byte[] loadUnsignedPdf(UUID docId) {
        return jdbc.sql("SELECT unsigned_pdf FROM properia.document_signatures WHERE id = :doc")
            .param("doc", docId)
            .query((rs, n) -> rs.getBytes("unsigned_pdf"))
            .single();
    }

    /** Descodifica e valida o PDF carregado (base64). Rejeita ficheiros que não sejam PDF. */
    private byte[] decodePdf(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Anexa o ficheiro PDF do documento.", 422);
        }
        var clean = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new DomainException("VALIDATION_ERROR", "Ficheiro inválido.", 422);
        }
        boolean isPdf = bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        if (!isPdf) {
            throw new DomainException("VALIDATION_ERROR", "O ficheiro tem de ser um PDF.", 422);
        }
        if (bytes.length > 10_000_000) {
            throw new DomainException("FILE_TOO_LARGE", "O PDF é demasiado grande (máx. 10 MB).", 413);
        }
        return bytes;
    }

    private List<SignatureSlot> loadSignerSlots(UUID docId) {
        return jdbc.sql("""
                SELECT role_label, signer_name, signed_at, signer_ip, signer_user_agent, signature_image
                FROM properia.document_signers WHERE document_id = :doc ORDER BY sign_order
                """)
            .param("doc", docId)
            .query((rs, n) -> {
                var stamp = new SignatureStamp(
                    rs.getString("signer_name"),
                    rs.getTimestamp("signed_at") == null ? Instant.now() : rs.getTimestamp("signed_at").toInstant(),
                    rs.getString("signer_ip"), rs.getString("signer_user_agent"),
                    rs.getBytes("signature_image"), docId.toString());
                return new SignatureSlot(rs.getString("role_label"), stamp);
            })
            .list();
    }

    // ── Listar / verificar / detalhe ──────────────────────────────────────────────

    public List<SignatureDto> list(UUID advertiserId) {
        return jdbc.sql(dtoSelect("WHERE d.advertiser_id = :adv ORDER BY d.created_at DESC LIMIT 200"))
            .param("adv", advertiserId)
            .query((rs, n) -> mapDto(rs))
            .list();
    }

    public SignatureDetail getDetail(UUID advertiserId, UUID id) {
        var dto = jdbc.sql(dtoSelect("WHERE d.id = :id AND d.advertiser_id = :adv"))
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> new Object[]{ mapDto(rs), readJsonList(rs.getString("audit_json")) })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));

        var signers = jdbc.sql("""
                SELECT role_label, signer_name, signer_email, status, sign_token, signed_at
                FROM properia.document_signers WHERE document_id = :id ORDER BY sign_order
                """)
            .param("id", id)
            .query((rs, n) -> new SignerInfo(
                rs.getString("role_label"), rs.getString("signer_name"), rs.getString("signer_email"),
                rs.getString("status"), rs.getString("sign_token"),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant()))
            .list();

        @SuppressWarnings("unchecked")
        var audit = (List<Map<String, Object>>) dto[1];
        return new SignatureDetail((SignatureDto) dto[0], audit, signers);
    }

    @Transactional
    public void cancel(UUID advertiserId, UUID id) {
        int updated = jdbc.sql("""
                UPDATE properia.document_signatures
                SET status = 'cancelled',
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                WHERE id = :id AND advertiser_id = :adv AND status <> 'signed'
                """)
            .param("event", writeJson(auditEvent("cancelled", null)))
            .param("id", id).param("adv", advertiserId)
            .update();
        if (updated == 0) {
            throw new DomainException("CANNOT_CANCEL",
                "Documento não encontrado ou já assinado (documentos assinados são imutáveis).", 409);
        }
        jdbc.sql("UPDATE properia.document_signers SET otp_code_hash = NULL WHERE document_id = :id")
            .param("id", id).update();
    }

    public VerifyResult verifyByHash(String hash) {
        return jdbc.sql("""
                SELECT title, signer_name, signed_at
                FROM properia.document_signatures
                WHERE document_hash = :hash AND status = 'signed'
                """)
            .param("hash", hash)
            .query((rs, n) -> new VerifyResult(true, rs.getString("title"),
                rs.getString("signer_name"),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant()))
            .optional()
            .orElse(new VerifyResult(false, null, null, null));
    }

    private SignatureDto findDto(UUID advertiserId, UUID id) {
        return jdbc.sql(dtoSelect("WHERE d.id = :id AND d.advertiser_id = :adv"))
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> mapDto(rs))
            .single();
    }

    private String dtoSelect(String where) {
        return """
                SELECT d.id, d.document_type, d.title, d.status, d.signer_name, d.signer_email,
                       d.document_hash, d.created_at, d.signed_at, d.audit::text AS audit_json,
                       (SELECT count(*) FROM properia.document_signers x WHERE x.document_id = d.id) AS signer_count,
                       (SELECT count(*) FROM properia.document_signers x WHERE x.document_id = d.id AND x.status = 'signed') AS signed_count
                FROM properia.document_signatures d
                """ + where;
    }

    private SignatureDto mapDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SignatureDto(
            rs.getString("id"), rs.getString("document_type"), rs.getString("title"),
            rs.getString("status"), rs.getString("signer_name"), rs.getString("signer_email"),
            rs.getString("document_hash"),
            rs.getInt("signer_count"), rs.getInt("signed_count"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Injeta identificação da agência + nº AMI (Lei 15/2013) no documento. */
    private void enrichWithAgency(UUID advertiserId, Map<String, Object> payload) {
        jdbc.sql("""
                SELECT legal_name, brand_name, license_number, tax_number
                FROM properia.advertisers WHERE id = :adv
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var legal = rs.getString("legal_name");
                var brand = rs.getString("brand_name");
                payload.putIfAbsent("agencyName", (brand != null && !brand.isBlank()) ? brand : legal);
                if (legal != null) payload.put("agencyLegalName", legal);
                if (rs.getString("license_number") != null) payload.put("amiLicense", rs.getString("license_number"));
                if (rs.getString("tax_number") != null) payload.put("agencyTaxNumber", rs.getString("tax_number"));
                return true;
            })
            .optional();
    }

    private Map<String, Object> auditEvent(String type, String ip) {
        var m = new LinkedHashMap<String, Object>();
        m.put("event", type);
        m.put("at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        if (ip != null) m.put("ip", ip);
        return m;
    }

    private byte[] decodeSignature(String base64) {
        if (base64 == null || base64.isBlank()) return new byte[0];
        var clean = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        try {
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private String generateOtp() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    private String randomToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonList(String s) {
        try {
            return s == null || s.isBlank() ? List.of() : json.readValue(s, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String s) {
        try {
            return s == null || s.isBlank() ? new LinkedHashMap<>() : json.readValue(s, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
