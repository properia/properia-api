package pt.properia.api.modules.listingimport.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.enrichment.vision.infrastructure.OpenAIProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O "cérebro" do importador: mapeia as colunas/tags arbitrárias de um export de
 * CRM/portal para os campos do Properia.
 *
 * Decisão de custo: UMA chamada à OpenAI por importação (não por linha). Envia os
 * nomes das colunas + 2-3 linhas de amostra e recebe um mapa campo→coluna. O
 * mapeamento é depois aplicado de forma determinística a todas as linhas — logo o
 * custo não cresce com o tamanho do ficheiro.
 *
 * Se a OpenAI não estiver configurada ou falhar, cai para um mapeamento por
 * heurística de aliases (funciona bem para feeds standard e CSVs bem nomeados).
 */
@Service
public class ListingImportMappingService {

    private static final Logger log = LoggerFactory.getLogger(ListingImportMappingService.class);

    /** Campos-alvo do Properia que queremos preencher a partir da origem. */
    public static final List<String> TARGET_FIELDS = List.of(
        "externalRef", "businessType", "propertyType", "title", "description",
        "price", "bedrooms", "bathrooms", "garageSpaces", "parkingSpaces",
        "usableAreaM2", "grossAreaM2", "lotAreaM2",
        "city", "district", "municipality", "parish", "neighborhood", "street", "postalCode",
        "latitude", "longitude", "condition", "furnished", "energyRating", "typology"
    );

    private static final String SYSTEM_PROMPT = """
        You map columns from a real-estate CRM/portal export to Properia's listing fields.
        You receive the list of SOURCE COLUMNS and a few SAMPLE ROWS.
        Return ONLY a valid JSON object (no markdown) with this exact structure:
        {
          "detectedSource": "Idealista XML | Kyero feed | eGO CSV | generic CSV | ...",
          "mapping": { "<propertiaField>": "<exact source column name or null>" },
          "confidence": { "<propertiaField>": 0.0 }
        }
        Properia fields (map each, use null when no column fits):
        externalRef, businessType, propertyType, title, description, price, bedrooms,
        bathrooms, garageSpaces, parkingSpaces, usableAreaM2, grossAreaM2, lotAreaM2,
        city, district, municipality, parish, neighborhood, street, postalCode,
        latitude, longitude, condition, furnished, energyRating, typology.
        Rules:
        - "typology" is a T0/T1/T2 style code column if present (used to derive bedrooms).
        - Column names in "mapping" MUST match the source column names EXACTLY (case included).
        - price = sale price or monthly rent amount. usableAreaM2 = net/living area; grossAreaM2 = built area; lotAreaM2 = plot/land area.
        - confidence is 0.0-1.0 per field.
        Return ONLY the JSON object.
        """;

    private final OpenAIProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public ListingImportMappingService(OpenAIProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public record MappingResult(String detectedSource, Map<String, String> mapping, Map<String, Double> confidence, boolean usedAi) {}

    public MappingResult buildMapping(List<String> columns, List<Map<String, String>> sampleRows) {
        if (props.isConfigured()) {
            try {
                return callOpenAi(columns, sampleRows);
            } catch (Exception e) {
                log.warn("AI column mapping failed, falling back to heuristics: {}", e.getMessage());
            }
        }
        return heuristicMapping(columns);
    }

    // ── OpenAI ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private MappingResult callOpenAi(List<String> columns, List<Map<String, String>> sampleRows) throws Exception {
        var samples = sampleRows.stream().limit(3).toList();
        var userPayload = new LinkedHashMap<String, Object>();
        userPayload.put("sourceColumns", columns);
        userPayload.put("sampleRows", samples);

        var requestBody = Map.of(
            "model", props.getVisionModel(),
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", json.writeValueAsString(userPayload))
            ),
            "max_tokens", 900,
            "temperature", 0.0,
            "response_format", Map.of("type", "json_object")
        );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(props.getUrl() + "/chat/completions"))
            .header("Authorization", "Bearer " + props.getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
            .timeout(Duration.ofMillis(props.getVisionTimeoutMs()))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI status " + response.statusCode() + ": " + response.body());
        }

        var content = json.readTree(response.body())
            .path("choices").path(0).path("message").path("content").asText().trim();
        var clean = content.startsWith("```")
            ? content.replaceAll("(?s)^```(json)?", "").replaceAll("(?s)```$", "").trim()
            : content;
        var node = json.readTree(clean);

        var detected = node.path("detectedSource").asText("Formato detetado");
        var mapping = new LinkedHashMap<String, String>();
        var confidence = new LinkedHashMap<String, Double>();
        var validColumns = new java.util.HashSet<>(columns);

        var mappingNode = node.path("mapping");
        var confNode = node.path("confidence");
        for (var field : TARGET_FIELDS) {
            var col = mappingNode.path(field);
            if (col.isTextual() && validColumns.contains(col.asText())) {
                mapping.put(field, col.asText());
                var c = confNode.path(field);
                confidence.put(field, c.isNumber() ? c.asDouble() : 0.7);
            }
        }
        // Se a IA não acertou nada, complementa com heurística.
        if (mapping.isEmpty()) return heuristicMapping(columns);
        return new MappingResult(detected, mapping, confidence, true);
    }

    // ── Heurística de fallback (aliases) ────────────────────────────────────────

    private static final Map<String, List<String>> ALIASES = buildAliases();

    private MappingResult heuristicMapping(List<String> columns) {
        var mapping = new LinkedHashMap<String, String>();
        var confidence = new LinkedHashMap<String, Double>();
        var normalizedToOriginal = new LinkedHashMap<String, String>();
        for (var col : columns) normalizedToOriginal.putIfAbsent(ListingImportNormalizer.key(col), col);

        for (var field : TARGET_FIELDS) {
            for (var alias : ALIASES.getOrDefault(field, List.of())) {
                var original = normalizedToOriginal.get(ListingImportNormalizer.key(alias));
                if (original != null) {
                    mapping.put(field, original);
                    confidence.put(field, 0.6);
                    break;
                }
            }
        }
        return new MappingResult("Mapeamento automático", mapping, confidence, false);
    }

    private static Map<String, List<String>> buildAliases() {
        var m = new LinkedHashMap<String, List<String>>();
        m.put("externalRef", List.of("ref", "reference", "referencia", "id", "external_id", "codigo", "cod"));
        m.put("businessType", List.of("business_type", "operation", "negocio", "tipo_negocio", "transaction", "finalidade", "price_freq"));
        m.put("propertyType", List.of("property_type", "type", "tipo", "tipo_imovel", "typology_type", "category", "tipologia_tipo"));
        m.put("title", List.of("title", "titulo", "name", "nome", "designacao"));
        m.put("description", List.of("description", "descricao", "desc", "observacoes", "notes", "detalhe", "resumo"));
        m.put("price", List.of("price", "preco", "valor", "amount", "price_amount", "preco_venda"));
        m.put("bedrooms", List.of("bedrooms", "beds", "quartos", "num_quartos", "assoalhadas", "dormitorios"));
        m.put("bathrooms", List.of("bathrooms", "baths", "casas_banho", "wc", "num_wc", "banheiros"));
        m.put("garageSpaces", List.of("garage", "garagem", "garage_spaces", "lugares_garagem"));
        m.put("parkingSpaces", List.of("parking", "estacionamento", "parking_spaces", "lugares"));
        m.put("usableAreaM2", List.of("usable_area", "area_util", "living_area", "surface_area_built", "built", "area_bruta_privativa"));
        m.put("grossAreaM2", List.of("gross_area", "area_bruta", "total_area", "surface_area", "construida"));
        m.put("lotAreaM2", List.of("lot_area", "area_terreno", "plot", "land_area", "surface_area_plot", "terreno"));
        m.put("city", List.of("city", "cidade", "town", "localidade", "locality"));
        m.put("district", List.of("district", "distrito", "province", "provincia"));
        m.put("municipality", List.of("municipality", "concelho", "municipio", "council"));
        m.put("parish", List.of("parish", "freguesia"));
        m.put("neighborhood", List.of("neighborhood", "bairro", "zona", "zone", "area_name"));
        m.put("street", List.of("street", "rua", "morada", "address", "location_detail"));
        m.put("postalCode", List.of("postal_code", "codigo_postal", "zip", "cp", "postcode"));
        m.put("latitude", List.of("latitude", "lat"));
        m.put("longitude", List.of("longitude", "lng", "lon", "long"));
        m.put("condition", List.of("condition", "estado", "estado_conservacao", "conservacao", "condicao"));
        m.put("furnished", List.of("furnished", "mobilado", "mobilia", "furniture"));
        m.put("energyRating", List.of("energy_rating", "certificado_energetico", "energy", "energy_certificate", "classe_energetica"));
        m.put("typology", List.of("typology", "tipologia", "type_code"));
        return m;
    }
}
