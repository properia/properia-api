package pt.properia.api.modules.signatures.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.shared.domain.DomainException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assinatura Eletrónica Simples (SES) com prova forte, self-hosted:
 * criar ficha de visita → enviar link + OTP por email → cliente assina → PDF carimbado
 * com trilho de auditoria + hash SHA-256 para verificação de integridade.
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

    public record CreateRequest(
        String documentType, String title, String signerName, String signerEmail,
        UUID leadId, UUID visitId, Map<String, Object> payload) {}

    public record SignatureDto(
        String id, String documentType, String title, String status,
        String signerName, String signerEmail, String documentHash,
        Instant createdAt, Instant signedAt) {}

    public record PublicView(
        String title, String signerName, String signerEmail, String status,
        String documentType, boolean signed, String agencyName) {}

    public record SignResult(String documentHash, Instant signedAt) {}

    public record VerifyResult(boolean valid, String title, String signerName, Instant signedAt) {}

    /** Detalhe para o agente: DTO + link de assinatura + timeline de auditoria. */
    public record SignatureDetail(SignatureDto item, String signToken, List<Map<String, Object>> auditTrail) {}

    // ── Criar ────────────────────────────────────────────────────────────────────

    @Transactional
    public SignatureDto create(UUID advertiserId, UUID userId, CreateRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Título do documento em falta.", 422);
        }
        if (req.signerName() == null || req.signerName().isBlank()
            || req.signerEmail() == null || req.signerEmail().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Nome e email do cliente são obrigatórios.", 422);
        }
        var payload = new LinkedHashMap<String, Object>();
        if (req.payload() != null) payload.putAll(req.payload());
        enrichWithAgency(advertiserId, payload);
        byte[] unsigned = pdfService.buildVisitForm(payload, null);
        String token = randomToken();

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
            .param("type", req.documentType() == null ? "visit_form" : req.documentType())
            .param("title", req.title())
            .param("payload", writeJson(payload))
            .param("unsigned", unsigned)
            .param("token", token)
            .param("signerName", req.signerName())
            .param("signerEmail", req.signerEmail())
            .param("audit", writeJson(List.of(auditEvent("created", null))))
            .query(UUID.class).single();

        return findDto(advertiserId, id);
    }

    // ── Enviar (gera OTP + email) ─────────────────────────────────────────────────

    @Transactional
    public void send(UUID advertiserId, UUID id) {
        var row = jdbc.sql("""
                SELECT title, status, sign_token, signer_name, signer_email
                FROM properia.document_signatures
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> Map.of(
                "title", rs.getString("title"),
                "status", rs.getString("status"),
                "token", rs.getString("sign_token"),
                "signerName", rs.getString("signer_name"),
                "signerEmail", rs.getString("signer_email")))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));

        if ("signed".equals(row.get("status"))) {
            throw new DomainException("ALREADY_SIGNED", "Este documento já foi assinado.", 409);
        }
        if ("cancelled".equals(row.get("status"))) {
            throw new DomainException("CANCELLED", "Este documento foi anulado — cria um novo.", 409);
        }

        String otp = generateOtp();
        jdbc.sql("""
                UPDATE properia.document_signatures
                SET status = 'sent', otp_code_hash = :otpHash,
                    otp_expires_at = now() + interval '15 minutes', otp_attempts = 0,
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("otpHash", sha256Hex(otp))
            .param("event", writeJson(auditEvent("sent", null)))
            .param("id", id).param("adv", advertiserId)
            .update();

        email.sendSignatureRequest(
            (String) row.get("signerEmail"),
            (String) row.get("signerName"),
            (String) row.get("title"),
            otp,
            (String) row.get("token"));
    }

    // ── Vista pública + PDF ───────────────────────────────────────────────────────

    @Transactional
    public PublicView getPublicView(String token) {
        var view = jdbc.sql("""
                SELECT title, signer_name, signer_email, document_type,
                       payload->>'agencyName' AS agency_name,
                       -- Expiração aplicada na leitura: link vencido aparece como 'expired'.
                       CASE WHEN status NOT IN ('signed','cancelled') AND expires_at < now()
                            THEN 'expired' ELSE status END AS status
                FROM properia.document_signatures WHERE sign_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> new PublicView(
                rs.getString("title"), rs.getString("signer_name"), rs.getString("signer_email"),
                rs.getString("status"), rs.getString("document_type"),
                "signed".equals(rs.getString("status")), rs.getString("agency_name")))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado ou link inválido.", 404));

        // Marca como visto (sem sobrescrever 'signed').
        jdbc.sql("""
                UPDATE properia.document_signatures
                SET status = CASE WHEN status = 'sent' THEN 'viewed' ELSE status END,
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                WHERE sign_token = :token
                """)
            .param("event", writeJson(auditEvent("viewed", null)))
            .param("token", token)
            .update();

        return view;
    }

    public byte[] getPublicPdf(String token) {
        return jdbc.sql("""
                SELECT COALESCE(signed_pdf, unsigned_pdf) AS pdf
                FROM properia.document_signatures WHERE sign_token = :token
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

    // ── Assinar ───────────────────────────────────────────────────────────────────

    @Transactional
    public SignResult sign(String token, String otp, String signatureImageBase64,
                           String ip, String userAgent) {
        var row = jdbc.sql("""
                SELECT id, status, payload, otp_code_hash, otp_expires_at, otp_attempts, signer_name
                FROM properia.document_signatures WHERE sign_token = :token
                """)
            .param("token", token)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getObject("id", UUID.class));
                m.put("status", rs.getString("status"));
                m.put("payload", rs.getString("payload"));
                m.put("otpHash", rs.getString("otp_code_hash"));
                m.put("otpExpires", rs.getTimestamp("otp_expires_at") == null ? null : rs.getTimestamp("otp_expires_at").toInstant());
                m.put("attempts", rs.getInt("otp_attempts"));
                m.put("signerName", rs.getString("signer_name"));
                return m;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));

        var status = (String) row.get("status");
        if ("signed".equals(status)) {
            throw new DomainException("ALREADY_SIGNED", "Este documento já foi assinado.", 409);
        }
        if ("cancelled".equals(status)) {
            throw new DomainException("CANCELLED", "Este documento foi anulado pela agência.", 410);
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
            jdbc.sql("UPDATE properia.document_signatures SET otp_attempts = otp_attempts + 1 WHERE sign_token = :token")
                .param("token", token).update();
            throw new DomainException("OTP_INVALID", "Código incorreto.", 422);
        }

        var id = (UUID) row.get("id");
        Map<String, Object> payload = readJsonMap((String) row.get("payload"));
        byte[] signatureImage = decodeSignature(signatureImageBase64);

        var stamp = new DocumentPdfService.SignatureStamp(
            (String) row.get("signerName"), Instant.now(), ip, userAgent, signatureImage, id.toString());
        byte[] signed = pdfService.buildVisitForm(payload, stamp);
        String hash = sha256Hex(signed);

        jdbc.sql("""
                UPDATE properia.document_signatures
                SET signed_pdf = :signed, document_hash = :hash, status = 'signed',
                    signed_at = now(), signer_ip = :ip, signer_user_agent = :ua,
                    otp_code_hash = NULL,
                    retention_until = now() + interval '5 years',
                    audit = audit || CAST(:event AS jsonb), updated_at = now()
                WHERE sign_token = :token
                """)
            .param("signed", signed)
            .param("hash", hash)
            .param("ip", ip)
            .param("ua", userAgent)
            .param("event", writeJson(auditEvent("signed", ip)))
            .param("token", token)
            .update();

        return new SignResult(hash, Instant.now());
    }

    // ── Listar / verificar ────────────────────────────────────────────────────────

    public List<SignatureDto> list(UUID advertiserId) {
        return jdbc.sql("""
                SELECT id, document_type, title, status, signer_name, signer_email,
                       document_hash, created_at, signed_at
                FROM properia.document_signatures
                WHERE advertiser_id = :adv
                ORDER BY created_at DESC
                LIMIT 200
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> mapDto(rs))
            .list();
    }

    /** Detalhe do documento para o agente: timeline de auditoria + token do link de assinatura. */
    public SignatureDetail getDetail(UUID advertiserId, UUID id) {
        return jdbc.sql("""
                SELECT id, document_type, title, status, signer_name, signer_email,
                       document_hash, created_at, signed_at, sign_token, audit::text AS audit_json
                FROM properia.document_signatures
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> new SignatureDetail(
                mapDto(rs),
                rs.getString("sign_token"),
                readJsonList(rs.getString("audit_json"))))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Documento não encontrado.", 404));
    }

    /** Anula um documento por assinar (o link deixa de funcionar). Assinados são imutáveis. */
    @Transactional
    public void cancel(UUID advertiserId, UUID id) {
        int updated = jdbc.sql("""
                UPDATE properia.document_signatures
                SET status = 'cancelled', otp_code_hash = NULL,
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
        return jdbc.sql("""
                SELECT id, document_type, title, status, signer_name, signer_email,
                       document_hash, created_at, signed_at
                FROM properia.document_signatures
                WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> mapDto(rs))
            .single();
    }

    private SignatureDto mapDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SignatureDto(
            rs.getString("id"), rs.getString("document_type"), rs.getString("title"),
            rs.getString("status"), rs.getString("signer_name"), rs.getString("signer_email"),
            rs.getString("document_hash"),
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
