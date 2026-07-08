package pt.properia.api.modules.listingimport.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pt.properia.api.shared.domain.DomainException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converte um ficheiro de import (CSV ou XML de feed imobiliário) numa lista de
 * registos crus (chave→valor de texto), preservando as colunas de origem.
 *
 * Suporta os formatos que ~90% dos CRMs ibéricos conseguem exportar sem contrato:
 *   • CSV (export universal de qualquer CRM/Excel)
 *   • XML de feed (Kyero v3, Idealista, Imovirtual/OLX, genérico) — deteta o
 *     elemento repetido (property/listing/imovel/anuncio) e achata os descendentes.
 *
 * XLSX (binário) fica para uma fase seguinte — a mensagem de erro orienta o
 * anunciante a exportar como CSV, que todos os sistemas suportam.
 */
@Component
public class ListingImportParser {

    private static final Logger log = LoggerFactory.getLogger(ListingImportParser.class);

    /** Nomes comuns do elemento que representa "um imóvel" num feed XML. */
    private static final List<String> XML_RECORD_TAGS = List.of(
        "property", "listing", "imovel", "imóvel", "anuncio", "anúncio", "item", "advert", "ad"
    );

    private static final Pattern IMAGE_URL = Pattern.compile(
        "^https?://\\S+?\\.(jpe?g|png|webp|gif|avif|bmp|tiff?)(\\?\\S*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_HTTP_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    /** Separadores usados por CRMs para juntar vários URLs numa só célula CSV. */
    private static final Pattern CSV_IMAGE_SPLIT = Pattern.compile("[\\s|;,]+");

    /** imagesByRow[i] são os URLs de imagem do registo records[i] (mesma ordem). */
    public record ParsedFile(List<String> columns, List<Map<String, String>> records,
                             List<List<String>> imagesByRow, String detectedFormat) {}

    public ParsedFile parse(String fileName, String content) {
        if (content == null || content.isBlank()) {
            throw new DomainException("EMPTY_FILE", "O ficheiro está vazio.", 422);
        }

        var trimmed = content.stripLeading();
        var isXml = trimmed.startsWith("<?xml") || trimmed.startsWith("<")
            || (fileName != null && fileName.toLowerCase().endsWith(".xml"));

        if (fileName != null && (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls"))) {
            throw new DomainException("UNSUPPORTED_FORMAT",
                "Ficheiros Excel (.xlsx) ainda não são suportados diretamente. "
                + "Exporta como CSV (Ficheiro → Guardar como → CSV) e volta a tentar.", 422);
        }

        return isXml ? parseXml(content) : parseCsv(content);
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    ParsedFile parseCsv(String content) {
        var rows = parseCsvText(content);
        var nonEmpty = new ArrayList<List<String>>();
        for (var row : rows) {
            if (row.stream().anyMatch(cell -> !cell.isBlank())) nonEmpty.add(row);
        }
        if (nonEmpty.isEmpty()) {
            throw new DomainException("EMPTY_FILE", "Não foi possível ler linhas do CSV.", 422);
        }

        var header = nonEmpty.get(0);
        var columns = new ArrayList<String>();
        for (var h : header) columns.add(h.trim());

        // Colunas que tipicamente carregam URLs de fotos.
        var imageColumns = new ArrayList<String>();
        for (var col : columns) {
            var k = normalizeCsvHeader(col);
            if (k.contains("image") || k.contains("photo") || k.contains("foto")
                || k.contains("picture") || k.contains("media") || k.contains("imagem")) {
                imageColumns.add(col);
            }
        }

        var records = new ArrayList<Map<String, String>>();
        var imagesByRow = new ArrayList<List<String>>();
        for (int i = 1; i < nonEmpty.size(); i++) {
            var row = nonEmpty.get(i);
            var record = new LinkedHashMap<String, String>();
            for (int c = 0; c < columns.size(); c++) {
                var value = c < row.size() ? row.get(c).trim() : "";
                if (!value.isEmpty()) record.put(columns.get(c), value);
            }
            if (record.isEmpty()) continue;
            records.add(record);

            var images = new LinkedHashSet<String>();
            for (var col : imageColumns) {
                var cell = record.get(col);
                if (cell == null) continue;
                for (var token : CSV_IMAGE_SPLIT.split(cell)) {
                    if (isImageUrl(token)) images.add(token.trim());
                }
            }
            imagesByRow.add(new ArrayList<>(images));
        }
        return new ParsedFile(columns, records, imagesByRow, "CSV");
    }

    private String normalizeCsvHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[\\s_-]+", "");
    }

    private boolean isImageUrl(String value) {
        if (value == null) return false;
        var v = value.trim();
        if (IMAGE_URL.matcher(v).matches()) return true;
        // Alguns feeds servem imagens sem extensão (ex: .../image/12345). Aceita http(s)
        // desde que o caminho sugira media e não seja claramente outra coisa.
        return ANY_HTTP_URL.matcher(v).matches()
            && (v.toLowerCase().contains("photo") || v.toLowerCase().contains("image")
                || v.toLowerCase().contains("foto") || v.toLowerCase().contains("/media/")
                || v.toLowerCase().contains("cdn"));
    }

    /** Parser CSV RFC-4180 (aspas, aspas escapadas, quebras dentro de célula). */
    private List<List<String>> parseCsvText(String input) {
        var rows = new ArrayList<List<String>>();
        var currentRow = new ArrayList<String>();
        var cell = new StringBuilder();
        var insideQuotes = false;
        var text = input.startsWith("﻿") ? input.substring(1) : input;

        // Deteta separador dominante (`;` é comum em exports PT/Excel).
        var delimiter = text.chars().filter(ch -> ch == ';').count()
            > text.chars().filter(ch -> ch == ',').count() ? ';' : ',';

        for (int i = 0; i < text.length(); i++) {
            var ch = text.charAt(i);
            var next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if (ch == '"') {
                if (insideQuotes && next == '"') { cell.append('"'); i++; }
                else insideQuotes = !insideQuotes;
                continue;
            }
            if (ch == delimiter && !insideQuotes) {
                currentRow.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            if ((ch == '\n' || ch == '\r') && !insideQuotes) {
                if (ch == '\r' && next == '\n') i++;
                currentRow.add(cell.toString());
                rows.add(new ArrayList<>(currentRow));
                currentRow.clear();
                cell.setLength(0);
                continue;
            }
            cell.append(ch);
        }
        if (cell.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(cell.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    // ── XML feeds ─────────────────────────────────────────────────────────────

    ParsedFile parseXml(String content) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // Segurança: desativa entidades externas (XXE).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            var doc = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            var recordElements = findRecordElements(doc);
            if (recordElements.isEmpty()) {
                throw new DomainException("EMPTY_FILE",
                    "Não foi possível identificar imóveis no XML. Verifica se é um feed de portal (Kyero, Idealista, etc.).", 422);
            }

            var columns = new LinkedHashSet<String>();
            var records = new ArrayList<Map<String, String>>();
            var imagesByRow = new ArrayList<List<String>>();
            for (var el : recordElements) {
                var record = new LinkedHashMap<String, String>();
                flatten(el, "", record);
                if (!record.isEmpty()) {
                    columns.addAll(record.keySet());
                    records.add(record);
                    imagesByRow.add(extractXmlImages(el));
                }
            }
            return new ParsedFile(new ArrayList<>(columns), records, imagesByRow, "XML feed");
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("XML parse failed: {}", e.getMessage());
            throw new DomainException("PARSE_ERROR",
                "Não foi possível ler o XML: " + e.getMessage(), 422);
        }
    }

    /** Encontra o conjunto de elementos que representam "um imóvel" cada. */
    private List<Element> findRecordElements(Document doc) {
        // 1) Tenta os nomes conhecidos de elemento-registo.
        for (var tag : XML_RECORD_TAGS) {
            var nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0) return toElementList(nodes);
        }
        // 2) Fallback: o elemento cujo nome se repete mais vezes como filho direto de algum nó.
        var root = doc.getDocumentElement();
        var children = root.getChildNodes();
        var counts = new LinkedHashMap<String, List<Element>>();
        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                counts.computeIfAbsent(node.getNodeName(), k -> new ArrayList<>()).add((Element) node);
            }
        }
        return counts.values().stream()
            .max((a, b) -> Integer.compare(a.size(), b.size()))
            .orElse(new ArrayList<>());
    }

    private List<Element> toElementList(NodeList nodes) {
        var list = new ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) list.add((Element) nodes.item(i));
        }
        return list;
    }

    /** Recolhe, por ordem, os URLs de imagem dentro do subárvore de um imóvel. */
    private List<String> extractXmlImages(Element record) {
        var out = new LinkedHashSet<String>();
        collectImageUrls(record, out);
        return new ArrayList<>(out);
    }

    private void collectImageUrls(Element element, LinkedHashSet<String> out) {
        // Atributos (ex: <image url="https://..."/>).
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var value = attrs.item(i).getNodeValue();
            if (isImageUrl(value)) out.add(value.trim());
        }
        var children = element.getChildNodes();
        var hasChildElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) { hasChildElements = true; break; }
        }
        if (!hasChildElements) {
            var text = element.getTextContent();
            if (isImageUrl(text)) out.add(text.trim());
            return;
        }
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                collectImageUrls((Element) children.item(i), out);
            }
        }
    }

    /** Achata a árvore de um imóvel em pares chave→texto (chave = caminho por "_"). */
    private void flatten(Element element, String prefix, Map<String, String> out) {
        var children = element.getChildNodes();
        var hasChildElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) { hasChildElements = true; break; }
        }

        // Atributos também podem carregar dados (ex: <area unit="m2">120</area>).
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var attr = attrs.item(i);
            var key = (prefix.isEmpty() ? element.getNodeName() : prefix) + "_" + attr.getNodeName();
            var value = attr.getNodeValue();
            if (value != null && !value.isBlank()) out.putIfAbsent(key, value.trim());
        }

        if (!hasChildElements) {
            var text = element.getTextContent();
            if (text != null && !text.isBlank()) {
                var key = prefix.isEmpty() ? element.getNodeName() : prefix;
                out.putIfAbsent(key, text.trim());
            }
            return;
        }

        for (int i = 0; i < children.getLength(); i++) {
            var node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            var child = (Element) node;
            var childPrefix = prefix.isEmpty() ? child.getNodeName() : prefix + "_" + child.getNodeName();
            flatten(child, childPrefix, out);
        }
    }
}
