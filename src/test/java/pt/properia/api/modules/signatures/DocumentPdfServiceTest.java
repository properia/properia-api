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
}
