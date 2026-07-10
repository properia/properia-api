package pt.properia.api.modules.signatures.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Geração de PDF (Apache PDFBox) da Ficha de Visita — versão não assinada (para o
 * cliente rever) e versão assinada com bloco de assinatura + trilho de auditoria.
 *
 * Fonte Helvetica (WinAnsi) suporta os acentos do português; o texto é sanitizado
 * para trocar caracteres fora dessa codificação (—, aspas curvas, …) por equivalentes.
 */
@Service
public class DocumentPdfService {

    private static final DateTimeFormatter DT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("Europe/Lisbon"));

    private static final float MARGIN = 56f;
    private static final float WIDTH = PDRectangle.A4.getWidth();

    /** Dados de assinatura para carimbar no PDF; null = versão não assinada. */
    public record SignatureStamp(
        String signerName, Instant signedAt, String ip, String userAgent,
        byte[] signatureImagePng, String documentId) {}

    public byte[] buildVisitForm(Map<String, Object> payload, SignatureStamp stamp) {
        try (var doc = new PDDocument()) {
            var page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            float y;
            try (var cs = new PDPageContentStream(doc, page)) {
                float top = PDRectangle.A4.getHeight() - MARGIN;

                // Cabeçalho
                text(cs, PDType1Font.HELVETICA_BOLD, 18, MARGIN, top, "FICHA DE VISITA");
                String agency = str(payload, "agencyName");
                if (!agency.isBlank()) {
                    text(cs, PDType1Font.HELVETICA_BOLD, 10, MARGIN, top - 18, agency);
                }
                // Identificação legal da agência de mediação (Lei 15/2013): nome legal, NIF, AMI.
                var idParts = new java.util.ArrayList<String>();
                String legal = str(payload, "agencyLegalName");
                String nif = str(payload, "agencyTaxNumber");
                String ami = str(payload, "amiLicense");
                if (!legal.isBlank() && !legal.equals(agency)) idParts.add(legal);
                if (!nif.isBlank()) idParts.add("NIF " + nif);
                if (!ami.isBlank()) idParts.add("Lic. AMI " + ami);
                float headerBottom = top - 30;
                if (!idParts.isEmpty()) {
                    text(cs, PDType1Font.HELVETICA, 8, MARGIN, top - 30, String.join("  ·  ", idParts));
                    headerBottom = top - 42;
                }
                hr(cs, headerBottom);

                y = headerBottom - 26;
                y = field(cs, y, "Imóvel", str(payload, "listingTitle"));
                y = field(cs, y, "Morada", str(payload, "address"));
                y = field(cs, y, "Data da visita", str(payload, "visitDate"));
                y = field(cs, y, "Cliente", str(payload, "clientName"));
                String clientNif = str(payload, "clientNif");
                if (!clientNif.isBlank()) {
                    y = field(cs, y, "NIF do cliente", clientNif);
                }
                y = field(cs, y, "Contacto do cliente", str(payload, "clientContact"));
                y = field(cs, y, "Agente", str(payload, "agentName"));

                String notes = str(payload, "notes");
                if (!notes.isBlank()) {
                    y -= 6;
                    text(cs, PDType1Font.HELVETICA_BOLD, 10, MARGIN, y, "Observações");
                    y -= 15;
                    y = paragraph(cs, y, notes, 10);
                }

                // Declaração
                y -= 14;
                y = paragraph(cs, y,
                    "O cliente acima identificado declara ter visitado o imóvel indicado, "
                    + "acompanhado pelo agente, e reconhece a mediação da agência nesta visita. "
                    + "A assinatura eletrónica abaixo tem valor probatório nos termos do "
                    + "Regulamento (UE) 910/2014 (eIDAS).", 9);

                // Bloco de assinatura
                y -= 30;
                hr(cs, y);
                y -= 20;
                text(cs, PDType1Font.HELVETICA_BOLD, 10, MARGIN, y, "Assinatura do cliente");

                if (stamp != null) {
                    if (stamp.signatureImagePng() != null && stamp.signatureImagePng().length > 0) {
                        try {
                            var img = PDImageXObject.createFromByteArray(doc, stamp.signatureImagePng(), "sig");
                            float w = 180, h = 60;
                            cs.drawImage(img, MARGIN, y - h - 6, w, h);
                            y -= h + 12;
                        } catch (Exception ignored) {
                            y -= 12;
                        }
                    } else {
                        y -= 12;
                    }
                    text(cs, PDType1Font.HELVETICA, 10, MARGIN, y, sanitize(stamp.signerName()));
                    y -= 22;

                    // Trilho de auditoria
                    hr(cs, y);
                    y -= 16;
                    text(cs, PDType1Font.HELVETICA_BOLD, 9, MARGIN, y, "PROVA DE ASSINATURA ELETRÓNICA");
                    y -= 14;
                    y = auditLine(cs, y, "Assinado por: " + stamp.signerName());
                    y = auditLine(cs, y, "Data/hora: " + DT.format(stamp.signedAt()) + " (Europe/Lisbon)");
                    y = auditLine(cs, y, "Identidade verificada por código único enviado por email (OTP).");
                    if (stamp.ip() != null) y = auditLine(cs, y, "Endereço IP: " + stamp.ip());
                    y = auditLine(cs, y, "Referência do documento: " + stamp.documentId());
                } else {
                    y -= 40;
                    text(cs, PDType1Font.HELVETICA, 9, MARGIN, y,
                        "(Por assinar — o cliente assina eletronicamente através do link recebido por email.)");
                }
            }

            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Falha a gerar PDF da ficha de visita: " + e.getMessage(), e);
        }
    }

    // ── helpers de layout ────────────────────────────────────────────────────────

    private float field(PDPageContentStream cs, float y, String label, String value) throws Exception {
        text(cs, PDType1Font.HELVETICA_BOLD, 10, MARGIN, y, label);
        text(cs, PDType1Font.HELVETICA, 11, MARGIN + 150, y, value.isBlank() ? "—" : value);
        return y - 22;
    }

    private float auditLine(PDPageContentStream cs, float y, String s) throws Exception {
        text(cs, PDType1Font.HELVETICA, 8, MARGIN, y, s);
        return y - 12;
    }

    private void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(s));
        cs.endText();
    }

    private void hr(PDPageContentStream cs, float y) throws Exception {
        cs.moveTo(MARGIN, y);
        cs.lineTo(WIDTH - MARGIN, y);
        cs.setLineWidth(0.5f);
        cs.stroke();
    }

    /** Quebra o texto em linhas para caber na largura útil. */
    private float paragraph(PDPageContentStream cs, float y, String text, float size) throws Exception {
        float maxWidth = WIDTH - 2 * MARGIN;
        var words = sanitize(text).split("\\s+");
        var line = new StringBuilder();
        for (var word : words) {
            var attempt = line.isEmpty() ? word : line + " " + word;
            float w = PDType1Font.HELVETICA.getStringWidth(attempt) / 1000 * size;
            if (w > maxWidth && !line.isEmpty()) {
                text(cs, PDType1Font.HELVETICA, size, MARGIN, y, line.toString());
                y -= size + 4;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(attempt);
            }
        }
        if (!line.isEmpty()) {
            text(cs, PDType1Font.HELVETICA, size, MARGIN, y, line.toString());
            y -= size + 4;
        }
        return y;
    }

    private String str(Map<String, Object> m, String k) {
        var v = m.get(k);
        return v == null ? "" : v.toString();
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
