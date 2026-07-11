package pt.properia.api.modules.signatures.application;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sugere valores para os campos de um modelo de contrato a partir dos DADOS já
 * disponíveis (cliente, imóvel, agência). A IA apenas ESCOLHE o valor certo dos dados
 * fornecidos para cada campo — nunca inventa nem redige texto. Se a OpenAI não estiver
 * configurada ou falhar, cai para um mapeamento heurístico por semelhança de nomes.
 *
 * O anunciante revê sempre o resultado antes de enviar (é o responsável pela veracidade).
 */
@Service
public class TemplateFillService {

    private static final Logger log = LoggerFactory.getLogger(TemplateFillService.class);

    private static final String SYSTEM_PROMPT = """
        Preenches campos de um modelo de contrato imobiliário. Recebes:
        - "fields": os nomes dos campos do modelo (arbitrários, definidos pela agência).
        - "data": pares rótulo→valor com os dados disponíveis.
        Devolve APENAS um objeto JSON { "<nome_do_campo>": "<valor>" } em que cada campo
        recebe o valor de "data" que melhor lhe corresponde.
        REGRAS:
        - Usa SOMENTE valores presentes em "data". NUNCA inventes dados nem escrevas texto novo.
        - Se nenhum dado servir para um campo, devolve "" (string vazia) para esse campo.
        - Faz correspondência por significado (ex.: campo "nome_comprador" ou "adquirente" ←
          "Nome do cliente"; "preco"/"valor" ← "Preço do imóvel"; "ami" ← "Licença AMI").
        Responde só com o objeto JSON.
        """;

    private final OpenAIProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public TemplateFillService(OpenAIProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public Map<String, String> suggest(List<String> fieldNames, Map<String, String> data) {
        if (fieldNames == null || fieldNames.isEmpty()) return Map.of();
        if (props.isConfigured()) {
            try {
                return callOpenAi(fieldNames, data);
            } catch (Exception e) {
                log.warn("Sugestão de preenchimento por IA falhou, uso heurística: {}", e.getMessage());
            }
        }
        return heuristic(fieldNames, data);
    }

    private Map<String, String> callOpenAi(List<String> fieldNames, Map<String, String> data) throws Exception {
        var userPayload = new LinkedHashMap<String, Object>();
        userPayload.put("fields", fieldNames);
        userPayload.put("data", data);

        var requestBody = Map.of(
            "model", props.getVisionModel(),
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", json.writeValueAsString(userPayload))
            ),
            "max_tokens", 800,
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
            throw new RuntimeException("OpenAI status " + response.statusCode());
        }
        var content = json.readTree(response.body())
            .path("choices").path(0).path("message").path("content").asText().trim();
        var clean = content.startsWith("```")
            ? content.replaceAll("(?s)^```(json)?", "").replaceAll("(?s)```$", "").trim()
            : content;
        var node = json.readTree(clean);

        var out = new LinkedHashMap<String, String>();
        for (var field : fieldNames) {
            var v = node.path(field);
            // Só aceita valores que existam de facto nos dados — nunca "inventados".
            var value = v.isTextual() ? v.asText() : "";
            out.put(field, isFromData(value, data) ? value : "");
        }
        return out;
    }

    /** Aceita o valor sugerido apenas se corresponder (por igualdade) a um valor dos dados. */
    private boolean isFromData(String value, Map<String, String> data) {
        if (value == null || value.isBlank()) return false;
        var v = value.trim();
        return data.values().stream().anyMatch(d -> d != null && d.trim().equalsIgnoreCase(v));
    }

    // ── Fallback heurístico (sem IA): correspondência por tokens do nome ──────────

    private Map<String, String> heuristic(List<String> fieldNames, Map<String, String> data) {
        var out = new LinkedHashMap<String, String>();
        for (var field : fieldNames) {
            var fieldTokens = tokens(field);
            String best = "";
            int bestScore = 0;
            for (var entry : data.entrySet()) {
                int score = overlap(fieldTokens, tokens(entry.getKey()));
                if (score > bestScore) { bestScore = score; best = entry.getValue(); }
            }
            out.put(field, bestScore > 0 ? best : "");
        }
        return out;
    }

    private List<String> tokens(String s) {
        if (s == null) return List.of();
        return List.of(s.replaceAll("([a-z])([A-Z])", "$1 $2")
            .toLowerCase().replaceAll("[^a-z0-9áàâãéèêíóôõúç]+", " ").trim().split("\\s+"));
    }

    private int overlap(List<String> a, List<String> b) {
        int n = 0;
        for (var t : a) if (t.length() > 2 && b.contains(t)) n++;
        return n;
    }
}
