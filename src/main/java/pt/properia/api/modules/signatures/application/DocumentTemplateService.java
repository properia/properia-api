package pt.properia.api.modules.signatures.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.signatures.application.DocumentPdfService.FormFieldInfo;
import pt.properia.api.shared.domain.DomainException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelos de contrato próprios da agência (PDF prenchível / AcroForm). A agência carrega
 * o modelo redigido pelo jurista dela; detetamos os campos para depois os preencher com os
 * dados do formulário — sem gerar texto jurídico.
 */
@Service
public class DocumentTemplateService {

    private final JdbcClient jdbc;
    private final DocumentPdfService pdfService;
    private final TemplateFillService fillService;
    private final ObjectMapper json;

    public DocumentTemplateService(JdbcClient jdbc, DocumentPdfService pdfService,
                                   TemplateFillService fillService, ObjectMapper json) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.fillService = fillService;
        this.json = json;
    }

    public record TemplateListItem(String id, String name, String documentType,
                                   int fieldCount, java.time.Instant createdAt) {}

    public record TemplateDetail(String id, String name, String documentType,
                                 List<FormFieldInfo> fields, java.time.Instant createdAt) {}

    public record CreateTemplateRequest(String name, String documentType, String pdfBase64) {}

    public TemplateDetail create(UUID advertiserId, CreateTemplateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Dá um nome ao modelo.", 422);
        }
        byte[] pdf = decodePdf(req.pdfBase64());
        var fields = pdfService.detectFormFields(pdf);
        if (fields.isEmpty()) {
            throw new DomainException("NO_FORM_FIELDS",
                "Este PDF não tem campos prenchíveis (campos de formulário/AcroForm) — por isso não conseguimos "
                + "detetar onde inserir os dados. No Word/LibreOffice, insere \"Controlos de conteúdo\" ou "
                + "\"Campos de formulário\" nos espaços a preencher (nome, NIF, preço…) e depois exporta/imprime "
                + "para PDF. No Adobe Acrobat: Ferramentas → Preparar Formulário deteta os campos automaticamente.",
                422);
        }
        var id = jdbc.sql("""
                INSERT INTO properia.document_templates (advertiser_id, name, document_type, pdf_template, fields)
                VALUES (:adv, :name, :type, :pdf, CAST(:fields AS jsonb))
                RETURNING id
                """)
            .param("adv", advertiserId)
            .param("name", req.name().trim())
            .param("type", req.documentType() == null || req.documentType().isBlank() ? "custom" : req.documentType())
            .param("pdf", pdf)
            .param("fields", writeJson(fields))
            .query(UUID.class).single();
        return get(advertiserId, id);
    }

    public List<TemplateListItem> list(UUID advertiserId) {
        return jdbc.sql("""
                SELECT id, name, document_type, jsonb_array_length(fields) AS field_count, created_at
                FROM properia.document_templates
                WHERE advertiser_id = :adv ORDER BY created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new TemplateListItem(
                rs.getString("id"), rs.getString("name"), rs.getString("document_type"),
                rs.getInt("field_count"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()))
            .list();
    }

    public TemplateDetail get(UUID advertiserId, UUID id) {
        return jdbc.sql("""
                SELECT id, name, document_type, fields::text AS fields_json, created_at
                FROM properia.document_templates WHERE id = :id AND advertiser_id = :adv
                """)
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> new TemplateDetail(
                rs.getString("id"), rs.getString("name"), rs.getString("document_type"),
                readFields(rs.getString("fields_json")),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Modelo não encontrado.", 404));
    }

    public byte[] getPdf(UUID advertiserId, UUID id) {
        return jdbc.sql("SELECT pdf_template FROM properia.document_templates WHERE id = :id AND advertiser_id = :adv")
            .param("id", id).param("adv", advertiserId)
            .query((rs, n) -> rs.getBytes("pdf_template"))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Modelo não encontrado.", 404));
    }

    /**
     * Sugere valores para os campos do modelo a partir dos dados disponíveis (agência +
     * visita/imóvel). A IA só escolhe valores existentes; o anunciante revê antes de enviar.
     */
    public Map<String, String> suggestFill(UUID advertiserId, UUID templateId, UUID visitId, UUID listingId) {
        var fieldNames = get(advertiserId, templateId).fields().stream().map(FormFieldInfo::name).toList();
        var ctx = new java.util.LinkedHashMap<String, String>();

        // Agência (identificação + AMI)
        jdbc.sql("SELECT legal_name, brand_name, tax_number, license_number FROM properia.advertisers WHERE id = :adv")
            .param("adv", advertiserId)
            .query((rs, n) -> {
                put(ctx, "Nome da agência", coalesce(rs.getString("brand_name"), rs.getString("legal_name")));
                put(ctx, "NIF da agência", rs.getString("tax_number"));
                put(ctx, "Licença AMI", rs.getString("license_number"));
                return true;
            }).optional();

        // Visita → cliente + imóvel
        if (visitId != null) {
            jdbc.sql("""
                    SELECT l.contact_name, l.contact_email, l.contact_phone,
                           li.title, li.city, li.district, li.street, li.postal_code, li.price_amount
                    FROM properia.visits v
                    JOIN properia.leads l ON l.id = v.lead_id
                    JOIN properia.listings li ON li.id = v.listing_id
                    WHERE v.id = :vid AND v.advertiser_id = :adv
                    """)
                .param("vid", visitId).param("adv", advertiserId)
                .query((rs, n) -> { putListingAndLead(ctx, rs); return true; })
                .optional();
        } else if (listingId != null) {
            jdbc.sql("""
                    SELECT NULL AS contact_name, NULL AS contact_email, NULL AS contact_phone,
                           title, city, district, street, postal_code, price_amount
                    FROM properia.listings WHERE id = :lid AND advertiser_id = :adv
                    """)
                .param("lid", listingId).param("adv", advertiserId)
                .query((rs, n) -> { putListingAndLead(ctx, rs); return true; })
                .optional();
        }

        return fillService.suggest(fieldNames, ctx);
    }

    private void putListingAndLead(java.util.Map<String, String> ctx, java.sql.ResultSet rs) throws java.sql.SQLException {
        put(ctx, "Nome do cliente", rs.getString("contact_name"));
        put(ctx, "Email do cliente", rs.getString("contact_email"));
        put(ctx, "Telefone do cliente", rs.getString("contact_phone"));
        put(ctx, "Título do imóvel", rs.getString("title"));
        put(ctx, "Cidade do imóvel", rs.getString("city"));
        put(ctx, "Distrito do imóvel", rs.getString("district"));
        var morada = java.util.stream.Stream.of(rs.getString("street"), rs.getString("postal_code"), rs.getString("city"))
            .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + ", " + b).orElse(null);
        put(ctx, "Morada do imóvel", morada);
        var price = rs.getObject("price_amount");
        if (price != null) put(ctx, "Preço do imóvel (euros)", price.toString());
    }

    private void put(java.util.Map<String, String> ctx, String label, String value) {
        if (value != null && !value.isBlank()) ctx.put(label, value.trim());
    }

    private String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    public void delete(UUID advertiserId, UUID id) {
        int deleted = jdbc.sql("DELETE FROM properia.document_templates WHERE id = :id AND advertiser_id = :adv")
            .param("id", id).param("adv", advertiserId).update();
        if (deleted == 0) throw new DomainException("NOT_FOUND", "Modelo não encontrado.", 404);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private byte[] decodePdf(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Anexa o ficheiro PDF do modelo.", 422);
        }
        var clean = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw new DomainException("VALIDATION_ERROR", "Ficheiro inválido.", 422);
        }
        boolean isPdf = bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        if (!isPdf) throw new DomainException("VALIDATION_ERROR", "O ficheiro tem de ser um PDF.", 422);
        if (bytes.length > 10_000_000) throw new DomainException("FILE_TOO_LARGE", "O PDF é demasiado grande (máx. 10 MB).", 413);
        return bytes;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private List<FormFieldInfo> readFields(String s) {
        try {
            return s == null || s.isBlank() ? List.of()
                : json.readValue(s, json.getTypeFactory().constructCollectionType(List.class, FormFieldInfo.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
