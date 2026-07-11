package pt.properia.api.modules.signatures.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Geração de PDF (Apache PDFBox) dos documentos para assinatura — Ficha de Visita,
 * CPCV e Contrato de Mediação Imobiliária (CMI). Motor de layout com paginação
 * automática; cabeçalho (identificação da agência + AMI) e bloco de assinatura +
 * trilho de auditoria são partilhados por todos os tipos.
 */
@Service
public class DocumentPdfService {

    private static final DateTimeFormatter DT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("Europe/Lisbon"));

    private static final float MARGIN = 56f;
    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float BOTTOM = 64f;

    /** Dados de assinatura para carimbar no PDF; null = versão não assinada. */
    public record SignatureStamp(
        String signerName, Instant signedAt, String ip, String userAgent,
        byte[] signatureImagePng, String documentId) {}

    /** Uma "vaga" de assinatura: o rótulo da parte e a assinatura (null = ainda por assinar). */
    public record SignatureSlot(String roleLabel, SignatureStamp stamp) {}

    // ── API pública ────────────────────────────────────────────────────────────

    /** Compat: ficha de visita (1 signatário). */
    public byte[] buildVisitForm(Map<String, Object> payload, SignatureStamp stamp) {
        return buildDocument("visit_form", payload, stamp);
    }

    /** Compat: 1 signatário. */
    public byte[] buildDocument(String documentType, Map<String, Object> payload, SignatureStamp stamp) {
        var type = documentType == null ? "visit_form" : documentType;
        return buildMultiDocument(type, payload, List.of(new SignatureSlot(signerRoleFor(type), stamp)));
    }

    /** Documento com N vagas de assinatura (multi-parte). */
    public byte[] buildMultiDocument(String documentType, Map<String, Object> payload, List<SignatureSlot> slots) {
        var type = documentType == null ? "visit_form" : documentType;
        try (var d = new Doc()) {
            header(d, payload, titleFor(type));
            switch (type) {
                case "cpcv" -> cpcvBody(d, payload);
                case "cmi" -> cmiBody(d, payload);
                default -> visitBody(d, payload);
            }
            for (var slot : slots) {
                signatureBlock(d, slot.roleLabel(), slot.stamp());
            }
            return d.finish();
        } catch (Exception e) {
            throw new RuntimeException("Falha a gerar PDF (" + type + "): " + e.getMessage(), e);
        }
    }

    /**
     * Documento carregado pela agência (ex.: CPCV/CMI redigido pelo jurista): mantém o PDF
     * original intacto e anexa uma página de assinaturas + prova. A plataforma não altera
     * o conteúdo jurídico — só acrescenta a cerimónia de assinatura.
     */
    public byte[] appendSignatures(byte[] basePdf, Map<String, Object> payload, List<SignatureSlot> slots) {
        try (var loaded = org.apache.pdfbox.pdmodel.PDDocument.load(basePdf);
             var d = new Doc(loaded)) {
            header(d, payload, "ASSINATURAS");
            d.paragraph("As assinaturas eletrónicas abaixo referem-se ao documento das páginas anteriores. "
                + "Têm valor probatório nos termos do Regulamento (UE) 910/2014 (eIDAS).", 9);
            for (var slot : slots) {
                signatureBlock(d, slot.roleLabel(), slot.stamp());
            }
            return d.finish();
        } catch (Exception e) {
            throw new RuntimeException("Falha a anexar assinaturas ao PDF carregado: " + e.getMessage(), e);
        }
    }

    // ── Modelos AcroForm (PDF prenchível carregado pela agência) ─────────────────

    public record FormFieldInfo(String name, String type) {}

    /** Deteta os campos de formulário (AcroForm) de um PDF carregado. */
    public List<FormFieldInfo> detectFormFields(byte[] pdf) {
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) return List.of();
            var out = new ArrayList<FormFieldInfo>();
            for (var field : form.getFieldTree()) {
                if (field instanceof PDTerminalField && field.getFullyQualifiedName() != null) {
                    out.add(new FormFieldInfo(field.getFullyQualifiedName(), fieldType(field)));
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível ler os campos do PDF: " + e.getMessage(), e);
        }
    }

    private String fieldType(Object field) {
        if (field instanceof PDCheckBox) return "checkbox";
        if (field instanceof PDRadioButton) return "radio";
        if (field instanceof PDChoice) return "choice";
        return "text";
    }

    /** Preenche os campos do modelo com os valores e "achata" (torna não-editável). */
    public byte[] fillAndFlatten(byte[] pdf, Map<String, String> values) {
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            var form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) {
                for (var field : form.getFieldTree()) {
                    if (!(field instanceof PDTerminalField)) continue;
                    var name = field.getFullyQualifiedName();
                    if (name == null || !values.containsKey(name)) continue;
                    var val = values.get(name);
                    try {
                        if (field instanceof PDCheckBox cb) {
                            if (isTruthy(val)) cb.check(); else cb.unCheck();
                        } else if (field instanceof PDTextField || field instanceof PDChoice) {
                            field.setValue(val == null ? "" : val);
                        }
                    } catch (Exception ignored) {
                        // Valor incompatível com o campo (ex.: opção inexistente) — ignora.
                    }
                }
                form.flatten();
            }
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Não foi possível preencher o modelo: " + e.getMessage(), e);
        }
    }

    private boolean isTruthy(String v) {
        if (v == null) return false;
        var s = v.trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("sim") || s.equals("on") || s.equals("yes") || s.equals("x");
    }

    private String titleFor(String type) {
        return switch (type) {
            case "cpcv" -> "CONTRATO-PROMESSA DE COMPRA E VENDA";
            case "cmi" -> "CONTRATO DE MEDIAÇÃO IMOBILIÁRIA";
            default -> "FICHA DE VISITA";
        };
    }

    private String signerRoleFor(String type) {
        return switch (type) {
            case "cpcv" -> "Assinatura do promitente-comprador";
            case "cmi" -> "Assinatura do cliente (proprietário)";
            default -> "Assinatura do cliente";
        };
    }

    // ── Corpos por tipo ──────────────────────────────────────────────────────────

    private void visitBody(Doc d, Map<String, Object> p) throws Exception {
        d.field("Imóvel", str(p, "listingTitle"));
        d.field("Morada", str(p, "address"));
        d.field("Data da visita", str(p, "visitDate"));
        d.field("Cliente", str(p, "clientName"));
        if (!str(p, "clientNif").isBlank()) d.field("NIF do cliente", str(p, "clientNif"));
        d.field("Contacto do cliente", str(p, "clientContact"));
        d.field("Agente", str(p, "agentName"));
        String notes = str(p, "notes");
        if (!notes.isBlank()) {
            d.gap(6); d.heading("Observações"); d.paragraph(notes, 10);
        }
        d.gap(10);
        d.paragraph("O cliente acima identificado declara ter visitado o imóvel indicado, acompanhado pelo "
            + "agente, e reconhece a mediação da agência nesta visita. A assinatura eletrónica abaixo tem valor "
            + "probatório nos termos do Regulamento (UE) 910/2014 (eIDAS).", 9);
    }

    private void cpcvBody(Doc d, Map<String, Object> p) throws Exception {
        d.paragraph("Entre as partes a seguir identificadas é celebrado o presente contrato-promessa de compra "
            + "e venda, que se rege pelas cláusulas seguintes e, no omisso, pela lei portuguesa.", 9);
        d.gap(6);

        d.heading("1. Partes");
        d.subheading("Promitente-vendedor");
        d.field("Nome", str(p, "sellerName"));
        if (!str(p, "sellerNif").isBlank()) d.field("NIF", str(p, "sellerNif"));
        if (!str(p, "sellerAddress").isBlank()) d.field("Morada", str(p, "sellerAddress"));
        d.subheading("Promitente-comprador");
        d.field("Nome", str(p, "buyerName"));
        if (!str(p, "buyerNif").isBlank()) d.field("NIF", str(p, "buyerNif"));
        if (!str(p, "buyerAddress").isBlank()) d.field("Morada", str(p, "buyerAddress"));

        d.heading("2. Identificação do imóvel");
        if (!str(p, "propertyDescription").isBlank()) d.paragraph(str(p, "propertyDescription"), 10);
        d.field("Morada", str(p, "propertyAddress"));
        if (!str(p, "propertyArticle").isBlank()) d.field("Artigo matricial", str(p, "propertyArticle"));
        if (!str(p, "propertyConservatoria").isBlank()) d.field("Descrição na CRP", str(p, "propertyConservatoria"));
        if (!str(p, "propertyLicense").isBlank()) d.field("Licença de utilização", str(p, "propertyLicense"));

        d.heading("3. Preço e pagamento");
        d.field("Preço total", euro(p, "totalPrice"));
        d.field("Sinal já entregue", euro(p, "depositAmount"));
        if (!str(p, "remainingPaymentTerms").isBlank()) {
            d.subheading("Pagamento do remanescente");
            d.paragraph(str(p, "remainingPaymentTerms"), 10);
        }

        d.heading("4. Escritura");
        d.field("Prazo para a escritura", str(p, "deedDeadline"));

        if (!str(p, "specialConditions").isBlank()) {
            d.heading("5. Condições especiais");
            d.paragraph(str(p, "specialConditions"), 10);
        }

        d.gap(6);
        d.paragraph("As partes declaram aceitar o presente contrato-promessa. A assinatura eletrónica aposta tem "
            + "valor probatório nos termos do Regulamento (UE) 910/2014 (eIDAS). Sempre que a lei o exija, os efeitos "
            + "reais dependem de escritura pública ou documento particular autenticado.", 8);
    }

    private void cmiBody(Doc d, Map<String, Object> p) throws Exception {
        d.paragraph("É celebrado o presente contrato de mediação imobiliária, ao abrigo da Lei n.º 15/2013, de 8 "
            + "de fevereiro, entre a agência mediadora identificada no cabeçalho (titular da licença AMI aí indicada) "
            + "e o cliente a seguir identificado.", 9);
        d.gap(6);

        d.heading("1. Cliente");
        d.field("Nome", str(p, "clientName"));
        if (!str(p, "clientNif").isBlank()) d.field("NIF", str(p, "clientNif"));
        if (!str(p, "clientAddress").isBlank()) d.field("Morada", str(p, "clientAddress"));

        d.heading("2. Imóvel");
        if (!str(p, "propertyDescription").isBlank()) d.paragraph(str(p, "propertyDescription"), 10);
        d.field("Morada", str(p, "propertyAddress"));

        d.heading("3. Regime e condições");
        d.field("Regime", "exclusivo".equalsIgnoreCase(str(p, "mediationRegime")) ? "Exclusivo" : "Não exclusivo");
        d.field("Preço de venda pretendido", euro(p, "salePrice"));
        String comm = str(p, "commission");
        d.field("Remuneração da mediação", comm.isBlank() ? "—" : comm);
        d.field("Duração do contrato", str(p, "durationMonths").isBlank() ? "—" : str(p, "durationMonths") + " meses");

        if (!str(p, "specialConditions").isBlank()) {
            d.heading("4. Condições especiais");
            d.paragraph(str(p, "specialConditions"), 10);
        }

        d.gap(6);
        d.paragraph("O cliente declara ter sido informado do regime de mediação, da remuneração devida e das "
            + "condições do contrato, incluindo o direito de resolução quando aplicável. A assinatura eletrónica "
            + "aposta tem valor probatório nos termos do Regulamento (UE) 910/2014 (eIDAS).", 8);
    }

    // ── Cabeçalho e bloco de assinatura (partilhados) ────────────────────────────

    private void header(Doc d, Map<String, Object> p, String title) throws Exception {
        d.text(PDType1Font.HELVETICA_BOLD, 17, MARGIN, title);
        d.y -= 20;
        String agency = str(p, "agencyName");
        if (!agency.isBlank()) { d.text(PDType1Font.HELVETICA_BOLD, 10, MARGIN, agency); d.y -= 12; }

        var idParts = new ArrayList<String>();
        String legal = str(p, "agencyLegalName");
        String nif = str(p, "agencyTaxNumber");
        String ami = str(p, "amiLicense");
        if (!legal.isBlank() && !legal.equals(agency)) idParts.add(legal);
        if (!nif.isBlank()) idParts.add("NIF " + nif);
        if (!ami.isBlank()) idParts.add("Lic. AMI " + ami);
        if (!idParts.isEmpty()) { d.text(PDType1Font.HELVETICA, 8, MARGIN, String.join("  ·  ", idParts)); d.y -= 12; }

        d.hr();
        d.gap(8);
    }

    private void signatureBlock(Doc d, String role, SignatureStamp stamp) throws Exception {
        d.ensure(140);
        d.gap(10);
        d.hr();
        d.gap(8);
        d.text(PDType1Font.HELVETICA_BOLD, 10, MARGIN, role);
        d.y -= 8;

        if (stamp != null) {
            if (stamp.signatureImagePng() != null && stamp.signatureImagePng().length > 0) {
                try {
                    var img = PDImageXObject.createFromByteArray(d.pdf, stamp.signatureImagePng(), "sig");
                    float w = 180, h = 60;
                    d.cs.drawImage(img, MARGIN, d.y - h, w, h);
                    d.y -= h + 6;
                } catch (Exception ignored) { d.y -= 12; }
            } else { d.y -= 12; }
            d.text(PDType1Font.HELVETICA, 10, MARGIN, sanitize(stamp.signerName()));
            d.y -= 20;

            d.hr();
            d.gap(6);
            d.text(PDType1Font.HELVETICA_BOLD, 9, MARGIN, "PROVA DE ASSINATURA ELETRÓNICA");
            d.y -= 13;
            audit(d, "Assinado por: " + stamp.signerName());
            audit(d, "Data/hora: " + DT.format(stamp.signedAt()) + " (Europe/Lisbon)");
            audit(d, "Identidade verificada por código único enviado por email (OTP).");
            if (stamp.ip() != null) audit(d, "Endereço IP: " + stamp.ip());
            audit(d, "Referência do documento: " + stamp.documentId());
        } else {
            d.y -= 40;
            d.text(PDType1Font.HELVETICA, 9, MARGIN,
                "(Por assinar — assinatura eletrónica através do link recebido por email.)");
        }
    }

    private void audit(Doc d, String s) throws Exception {
        d.ensure(12);
        d.text(PDType1Font.HELVETICA, 8, MARGIN, s);
        d.y -= 12;
    }

    // ── Motor de layout com paginação ────────────────────────────────────────────

    private final class Doc implements AutoCloseable {
        final PDDocument pdf;
        final boolean ownsPdf;
        PDPage page;
        PDPageContentStream cs;
        float y;

        Doc() throws Exception { this.pdf = new PDDocument(); this.ownsPdf = true; newPage(); }

        /** Anexa páginas a um PDF já existente (ex.: documento carregado pela agência). */
        Doc(PDDocument existing) throws Exception { this.pdf = existing; this.ownsPdf = false; newPage(); }

        void newPage() throws Exception {
            if (cs != null) cs.close();
            page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            cs = new PDPageContentStream(pdf, page);
            y = PAGE_H - MARGIN;
        }

        void ensure(float needed) throws Exception {
            if (y - needed < BOTTOM) newPage();
        }

        void text(PDType1Font font, float size, float x, String s) throws Exception {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(s));
            cs.endText();
        }

        void gap(float h) { y -= h; }

        void hr() throws Exception {
            ensure(10);
            cs.moveTo(MARGIN, y);
            cs.lineTo(PAGE_W - MARGIN, y);
            cs.setLineWidth(0.5f);
            cs.stroke();
            y -= 4;
        }

        void heading(String s) throws Exception {
            ensure(28);
            gap(8);
            text(PDType1Font.HELVETICA_BOLD, 11, MARGIN, s);
            y -= 16;
        }

        void subheading(String s) throws Exception {
            ensure(18);
            text(PDType1Font.HELVETICA_BOLD, 9, MARGIN, s);
            y -= 13;
        }

        void field(String label, String value) throws Exception {
            ensure(20);
            text(PDType1Font.HELVETICA_BOLD, 10, MARGIN, label);
            String v = value == null || value.isBlank() ? "—" : value;
            // Valores longos passam para linha própria envolvida.
            float valWidth = PDType1Font.HELVETICA.getStringWidth(sanitize(v)) / 1000 * 11;
            if (valWidth <= PAGE_W - 2 * MARGIN - 160) {
                text(PDType1Font.HELVETICA, 11, MARGIN + 160, v);
                y -= 20;
            } else {
                y -= 14;
                paragraph(v, 10);
                y -= 4;
            }
        }

        void paragraph(String text, float size) throws Exception {
            float maxWidth = PAGE_W - 2 * MARGIN;
            for (String rawLine : sanitize(text).split("\n")) {
                var words = rawLine.split("\\s+");
                var line = new StringBuilder();
                for (var word : words) {
                    var attempt = line.isEmpty() ? word : line + " " + word;
                    float w = PDType1Font.HELVETICA.getStringWidth(attempt) / 1000 * size;
                    if (w > maxWidth && !line.isEmpty()) {
                        ensure(size + 4);
                        text(PDType1Font.HELVETICA, size, MARGIN, line.toString());
                        y -= size + 4;
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(attempt);
                    }
                }
                if (!line.isEmpty()) {
                    ensure(size + 4);
                    text(PDType1Font.HELVETICA, size, MARGIN, line.toString());
                    y -= size + 4;
                }
            }
        }

        byte[] finish() throws Exception {
            if (cs != null) { cs.close(); cs = null; }
            stampFooters();
            var baos = new ByteArrayOutputStream();
            pdf.save(baos);
            return baos.toByteArray();
        }

        /** Rodapé em todas as páginas: numeração + menção de verificação (marca profissional). */
        private void stampFooters() throws Exception {
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                var p = pdf.getPage(i);
                try (var fs = new PDPageContentStream(pdf, p, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    fs.beginText();
                    fs.setFont(PDType1Font.HELVETICA, 7);
                    fs.newLineAtOffset(MARGIN, 30);
                    fs.showText(sanitize("Assinado e verificável em properia.pt/verificar"));
                    fs.endText();
                    fs.beginText();
                    fs.setFont(PDType1Font.HELVETICA, 7);
                    fs.newLineAtOffset(PAGE_W - MARGIN - 70, 30);
                    fs.showText("Página " + (i + 1) + " de " + total);
                    fs.endText();
                }
            }
        }

        @Override
        public void close() throws Exception {
            if (cs != null) { try { cs.close(); } catch (Exception ignored) {} }
            if (ownsPdf) pdf.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String k) {
        var v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static final java.text.NumberFormat EUR =
        java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("pt-PT"));

    private static String euro(Map<String, Object> m, String k) {
        var v = m.get(k);
        if (v == null || v.toString().isBlank()) return "—";
        try {
            return EUR.format(Double.parseDouble(v.toString().replace(" ", "").replace(",", ".")));
        } catch (NumberFormatException e) {
            return v.toString();
        }
    }

    /** Troca caracteres fora do WinAnsi por equivalentes ASCII para evitar erros da fonte. */
    private String sanitize(String s) {
        if (s == null) return "";
        return s
            .replace('—', '-').replace('–', '-')
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace("…", "...")
            .replace(' ', ' ')
            .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
    }
}
