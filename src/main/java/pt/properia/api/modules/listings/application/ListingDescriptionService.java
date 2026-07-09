package pt.properia.api.modules.listings.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.enrichment.vision.infrastructure.OpenAIProperties;
import pt.properia.api.shared.domain.DomainException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera uma descrição de anúncio em português a partir dos FACTOS já introduzidos
 * pelo anunciante (campos do formulário) e, quando disponível, dos sinais da Vision AI
 * (o que foi realmente detetado nas fotos).
 *
 * Princípio (ver admin-listing-ai-prefill.ts no frontend): a IA NÃO escreve nada em
 * silêncio. Este serviço só corre por ação explícita do utilizador ("Gerar descrição"),
 * o texto devolvido é editável, e o prompt é RESTRITO aos factos fornecidos — proibido
 * inventar características (o anunciante é o responsável legal pela veracidade).
 */
@Service
public class ListingDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(ListingDescriptionService.class);

    private static final String SYSTEM_PROMPT = """
        És um copywriter imobiliário português. Escreves a descrição de um anúncio de imóvel
        para um portal (tipo Idealista/Imovirtual), em português europeu, tom profissional,
        claro e apelativo — sem clichés vazios nem superlativos exagerados.

        REGRAS ABSOLUTAS:
        - Usa APENAS os factos fornecidos no JSON. NUNCA inventes características, áreas,
          divisões, acabamentos, vistas ou localizações que não estejam nos dados.
        - Se um facto não for fornecido, simplesmente não o menciones.
        - Não inventes números (preço, área, ano). Usa só os que vierem nos dados.
        - Os "visionSignals" descrevem o que a IA detetou nas fotos — podes referi-los, mas
          com naturalidade e sem exagerar.
        - Não incluas informação de contacto, nome de agência, nem chamadas à ação genéricas
          do tipo "marque já a sua visita".
        - Não uses markdown, títulos, listas com bullets nem emojis. Só texto corrido.

        FORMATO:
        - 2 a 3 parágrafos curtos (no total ~90 a 160 palavras).
        - 1º parágrafo: tipo de imóvel, tipologia, localização e enquadramento geral.
        - Parágrafos seguintes: divisões, áreas, estado/acabamentos e características relevantes.
        - Termina de forma natural, sem frase-clichê de venda.

        Responde APENAS com o texto da descrição, sem aspas nem prefixos.
        """;

    private final OpenAIProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public ListingDescriptionService(OpenAIProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * @param facts campos do formulário já preenchidos (tipo, negócio, zona, área, quartos,
     *              features, custos, etc.) — só os relevantes, sem nulls.
     * @return descrição gerada em texto corrido.
     */
    public String generate(Map<String, Object> facts) {
        if (!props.isConfigured()) {
            throw new DomainException("AI_UNAVAILABLE",
                "A geração por IA não está disponível de momento.", 503);
        }
        if (facts == null || facts.isEmpty()) {
            throw new DomainException("VALIDATION_ERROR",
                "Preenche primeiro alguns campos do imóvel para gerar a descrição.", 422);
        }
        try {
            return callOpenAi(facts);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Geração de descrição por IA falhou: {}", e.getMessage());
            throw new DomainException("AI_GENERATION_FAILED",
                "Não foi possível gerar a descrição agora. Tenta novamente.", 502);
        }
    }

    private String callOpenAi(Map<String, Object> facts) throws Exception {
        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", props.getVisionModel());
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", json.writeValueAsString(facts))
        ));
        requestBody.put("max_tokens", 500);
        requestBody.put("temperature", 0.6);

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
        // Remove aspas envolventes se o modelo as adicionar.
        if (content.length() > 1 && content.startsWith("\"") && content.endsWith("\"")) {
            content = content.substring(1, content.length() - 1).trim();
        }
        if (content.isBlank()) {
            throw new RuntimeException("OpenAI devolveu resposta vazia");
        }
        return content;
    }
}
