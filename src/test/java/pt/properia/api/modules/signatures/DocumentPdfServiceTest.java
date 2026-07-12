package pt.properia.api.modules.signatures;

import org.junit.jupiter.api.Test;
import pt.properia.api.modules.signatures.application.DocumentPdfService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do gerador de PDF da ficha de visita — sem Spring/DB.
 * Garante que produz PDFs válidos (não assinado e assinado) e que caracteres
 * portugueses/tipográficos não fazem a fonte rebentar (sanitização).
 */
class DocumentPdfServiceTest {

    private final DocumentPdfService service = new DocumentPdfService();

    private static boolean isPdf(byte[] bytes) {
        return bytes != null && bytes.length > 4
            && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private static byte[] tinyPng() throws Exception {
        var img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        var baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void generatesValidUnsignedPdf() {
        var payload = Map.<String, Object>of(
            "agencyName", "Imobiliária Sol",
            "listingTitle", "Apartamento T2 na Avenida",
            "address", "Rua das Flores, 10, Lisboa",
            "visitDate", "12/07/2026 às 15h",
            "clientName", "Ana Conceição",
            "agentName", "João Gonçalves"
        );

        byte[] pdf = service.buildVisitForm(payload, null);

        assertTrue(isPdf(pdf), "Deve produzir bytes de PDF válidos");
        assertTrue(pdf.length > 500, "PDF não deve estar vazio");
    }

    @Test
    void generatesValidSignedPdfWithSignatureImage() throws Exception {
        var payload = Map.<String, Object>of(
            "listingTitle", "Moradia com jardim",
            "clientName", "Ana Conceição"
        );
        var stamp = new DocumentPdfService.SignatureStamp(
            "Ana Conceição", Instant.now(), "203.0.113.7", "Mozilla/5.0",
            tinyPng(), "doc-123");

        byte[] pdf = service.buildVisitForm(payload, stamp);

        assertTrue(isPdf(pdf), "Deve produzir bytes de PDF válidos");
        // A versão assinada tem o bloco de auditoria, logo é maior que a vazia.
        assertTrue(pdf.length > 800);
    }

    @Test
    void doesNotThrowOnSpecialAndAccentedCharacters() {
        var payload = Map.<String, Object>of(
            "listingTitle", "T3 — “Vista mar” — luxo · ação",
            "notes", "Observações: pé-direito alto; ‘cozinha’ renovada — disponível já…",
            "clientName", "José Öztürk",
            "agentName", "María—João"
        );

        assertDoesNotThrow(() -> {
            byte[] pdf = service.buildVisitForm(payload, null);
            assertTrue(isPdf(pdf));
        });
    }

    @Test
    void handlesMissingFieldsGracefully() {
        byte[] pdf = service.buildVisitForm(Map.of(), null);
        assertTrue(isPdf(pdf), "Mesmo sem dados deve gerar um PDF (com traços nos campos)");
    }

    @Test
    void generatesCpcvWithMultiplePartiesAndPrices() {
        var payload = Map.<String, Object>ofEntries(
            Map.entry("agencyName", "Imobiliária Sol"),
            Map.entry("amiLicense", "AMI-12345"),
            Map.entry("agencyTaxNumber", "500123456"),
            Map.entry("sellerName", "José Silva"),
            Map.entry("sellerNif", "111222333"),
            Map.entry("buyerName", "Ana Conceição"),
            Map.entry("buyerNif", "444555666"),
            Map.entry("propertyAddress", "Rua das Flores, 10, Lisboa"),
            Map.entry("totalPrice", "250000"),
            Map.entry("depositAmount", "25000"),
            Map.entry("deedDeadline", "90 dias após a assinatura")
        );

        byte[] pdf = service.buildDocument("cpcv", payload, null);

        assertTrue(isPdf(pdf));
        assertTrue(pdf.length > 1000, "CPCV é um documento mais rico");
    }

    @Test
    void generatesCmiWithAmiLicense() {
        var payload = Map.<String, Object>of(
            "agencyName", "Imobiliária Sol", "amiLicense", "AMI-12345",
            "clientName", "Maria Santos", "clientNif", "123456789",
            "propertyAddress", "Av. da Liberdade, 5, Lisboa",
            "mediationRegime", "exclusivo", "salePrice", "300000",
            "commission", "5% + IVA", "durationMonths", "6"
        );

        byte[] pdf = service.buildDocument("cmi", payload, null);

        assertTrue(isPdf(pdf));
        assertTrue(pdf.length > 1000);
    }

    @Test
    void appendsSignaturePageToUploadedPdf() throws Exception {
        // Simula um "PDF carregado pela agência" gerando um base e anexando assinaturas.
        byte[] base = service.buildDocument("visit_form", Map.of("listingTitle", "T2"), null);
        var stamp = new DocumentPdfService.SignatureStamp(
            "Ana Conceição", Instant.now(), "203.0.113.7", "UA", tinyPng(), "doc-1");
        var slots = java.util.List.of(new DocumentPdfService.SignatureSlot("Promitente-comprador", stamp));

        byte[] out = service.appendSignatures(base, Map.of("agencyName", "Imobiliária Sol"), slots);

        assertTrue(isPdf(out), "Deve continuar a ser um PDF válido");
        assertTrue(out.length > base.length, "Anexar assinaturas deve aumentar o documento");
    }

    private static byte[] fillablePdf() throws Exception {
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
            var baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void detectsAndFillsAcroFormFields() throws Exception {
        byte[] pdf = fillablePdf();

        var fields = service.detectFormFields(pdf);
        assertEquals(1, fields.size());
        assertEquals("nome_comprador", fields.get(0).name());
        assertEquals("text", fields.get(0).type());

        byte[] filled = service.fillAndFlatten(pdf, Map.of("nome_comprador", "Ana Conceição"));
        assertTrue(isPdf(filled), "Modelo preenchido deve continuar a ser um PDF válido");
    }

    @Test
    void detectFormFieldsEmptyWhenNoAcroForm() {
        byte[] plain = service.buildVisitForm(Map.of("listingTitle", "T2"), null);
        assertTrue(service.detectFormFields(plain).isEmpty(), "PDF sem campos → lista vazia");
    }

    @Test
    void sampleTemplateIsValidPdfWithDetectableFields() {
        byte[] pdf = service.buildSampleTemplate();

        assertTrue(isPdf(pdf), "O modelo de exemplo deve ser um PDF válido");

        var fields = service.detectFormFields(pdf);
        assertFalse(fields.isEmpty(), "O modelo de exemplo tem de ter campos AcroForm detetáveis (é o propósito do ficheiro)");
        assertTrue(fields.stream().anyMatch(f -> f.name().equals("nome_comprador")));
        assertTrue(fields.stream().anyMatch(f -> f.type().equals("checkbox")),
            "Deve incluir um campo checkbox de exemplo, além dos campos de texto");
    }

    @Test
    void longContentPaginatesWithoutError() {
        var longText = "Cláusula ".repeat(400); // força múltiplas páginas
        var payload = Map.<String, Object>of(
            "propertyAddress", "Rua X", "specialConditions", longText, "totalPrice", "100000");

        assertDoesNotThrow(() -> {
            byte[] pdf = service.buildDocument("cpcv", payload, null);
            assertTrue(isPdf(pdf));
        });
    }
}
