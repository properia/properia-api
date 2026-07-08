package pt.properia.api.modules.advertiser.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import pt.properia.api.modules.enrichment.vision.infrastructure.OpenAIProperties;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.PlanAccessGuard;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
public class CopilotoController {

    private static final String SYSTEM_PROMPT = """
        És o Copiloto Properia — um assistente de CRM imobiliário seguro, especializado no mercado português.
        Tens acesso a dados reais da operação do anunciante (fornecidos abaixo em JSON).

        REGRAS:
        - Responde SEMPRE em português de Portugal.
        - Usa apenas os dados fornecidos no contexto. Não inventes leads, imóveis ou visitas.
        - Podes sugerir prioridades, rascunhos, planos e checklists.
        - NUNCA afirmes que enviaste mensagens, confirmaste visitas ou alteraste registos — apenas sugeres.
        - Se o utilizador pedir algo fora do âmbito imobiliário/CRM, recusa educadamente.
        - Formata a resposta com Markdown simples (negrito, listas). Sê conciso mas útil.
        - Se for relevante para a pergunta, inclui um bloco JSON no final com o formato:
          ```chart
          {"type":"bar","title":"...","entries":[{"label":"...","value":N},...]}
          ```
          Só inclui o bloco chart se a pergunta pede gráfico ou breakdown por origem/período.

        CONTEXTO DA OPERAÇÃO (hoje: %s):
        %s
        """;

    private final JdbcClient jdbc;
    private final OpenAIProperties openAiProps;
    private final ObjectMapper json;
    private final HttpClient http;
    private final PlanAccessGuard planGuard;

    public CopilotoController(JdbcClient jdbc, OpenAIProperties openAiProps, ObjectMapper json,
                             PlanAccessGuard planGuard) {
        this.jdbc = jdbc;
        this.openAiProps = openAiProps;
        this.json = json;
        this.planGuard = planGuard;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    @PostMapping("/api/advertiser/copiloto")
    public ResponseEntity<?> query(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {

        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        if (!openAiProps.isConfigured()) {
            throw new DomainException("AI_UNAVAILABLE", "O assistente IA não está configurado neste ambiente.", 503);
        }
        // Copiloto é Pro+ — impor no servidor (a IA consome tokens à nossa custa).
        planGuard.requireProFeatures(claims.activeAdvertiserId());

        var advertiserId = claims.activeAdvertiserId();
        var question = body.getOrDefault("question", "").toString().trim();
        if (question.isBlank()) {
            throw new DomainException("BAD_REQUEST", "Pergunta em falta.", 400);
        }

        @SuppressWarnings("unchecked")
        var history = body.containsKey("history")
            ? (List<Map<String, Object>>) body.get("history")
            : List.<Map<String, Object>>of();

        var startMs = System.currentTimeMillis();
        var context = buildContext(advertiserId);
        var answer = callOpenAI(context, question, history);
        var durationMs = System.currentTimeMillis() - startMs;

        // Extract optional chart block from answer
        Object chartData = null;
        var cleanAnswer = answer;
        var chartStart = answer.indexOf("```chart");
        if (chartStart >= 0) {
            var chartEnd = answer.indexOf("```", chartStart + 8);
            if (chartEnd > chartStart) {
                var chartJson = answer.substring(chartStart + 8, chartEnd).trim();
                cleanAnswer = (answer.substring(0, chartStart) + answer.substring(chartEnd + 3)).trim();
                try { chartData = json.readValue(chartJson, Object.class); } catch (Exception ignored) {}
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("answer", cleanAnswer);
        result.put("toolsCalled", List.of());
        result.put("durationMs", durationMs);
        result.put("refused", false);
        result.put("chartData", chartData);

        return ResponseEntity.ok(Map.of("data", result));
    }

    // ── Context builder ───────────────────────────────────────────────────────

    private Map<String, Object> buildContext(UUID advertiserId) {
        var ctx = new LinkedHashMap<String, Object>();
        try { ctx.put("operationSummary", loadOperationSummary(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("priorityLeads", loadPriorityLeads(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("leadsWithoutResponse", loadLeadsWithoutResponse(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("visitsToConfirm", loadVisitsToConfirm(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("listingPerformance", loadListingPerformance(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("importStatus", loadImportStatus(advertiserId)); } catch (Exception ignored) {}
        try { ctx.put("leadsBySource", loadLeadsBySource(advertiserId)); } catch (Exception ignored) {}
        return ctx;
    }

    private Map<String, Object> loadOperationSummary(UUID advertiserId) {
        return jdbc.sql("""
                SELECT
                  COUNT(DISTINCT l.id) FILTER (WHERE l.status = 'published') AS active_listings,
                  COUNT(DISTINCT l.id) FILTER (WHERE l.status = 'draft') AS draft_listings,
                  COUNT(DISTINCT ld.id) AS total_leads,
                  COUNT(DISTINCT ld.id) FILTER (WHERE ld.stage = 'new') AS new_leads,
                  COUNT(DISTINCT ld.id) FILTER (WHERE ld.stage IN ('new','contacted','visit_scheduled') AND ld.created_at > now() - interval '7 days') AS leads_this_week,
                  COUNT(DISTINCT v.id) FILTER (WHERE v.status = 'requested') AS visits_pending,
                  COUNT(DISTINCT v.id) FILTER (WHERE v.status = 'confirmed' AND v.starts_at > now()) AS visits_upcoming
                FROM properia.listings l
                LEFT JOIN properia.leads ld ON ld.listing_id = l.id
                LEFT JOIN properia.visits v ON v.advertiser_id = :adv
                WHERE l.advertiser_id = :adv
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("activeListings", rs.getInt("active_listings"));
                m.put("draftListings", rs.getInt("draft_listings"));
                m.put("totalLeads", rs.getInt("total_leads"));
                m.put("newLeads", rs.getInt("new_leads"));
                m.put("leadsThisWeek", rs.getInt("leads_this_week"));
                m.put("visitsPending", rs.getInt("visits_pending"));
                m.put("visitsUpcoming", rs.getInt("visits_upcoming"));
                return (Map<String, Object>) m;
            }).single();
    }

    private List<Map<String, Object>> loadPriorityLeads(UUID advertiserId) {
        return jdbc.sql("""
                SELECT ld.id, ld.contact_name, ld.stage, ld.source::text,
                       ld.created_at, ld.updated_at,
                       l.title AS listing_title, l.price_amount,
                       EXTRACT(EPOCH FROM (now() - ld.updated_at))/3600 AS hours_since_update
                FROM properia.leads ld
                JOIN properia.listings l ON l.id = ld.listing_id
                WHERE l.advertiser_id = :adv
                  AND ld.stage IN ('new', 'contacted', 'visit_scheduled', 'proposal')
                ORDER BY ld.updated_at ASC
                LIMIT 10
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("contactName", rs.getString("contact_name"));
                m.put("stage", rs.getString("stage"));
                m.put("source", rs.getString("source"));
                m.put("listingTitle", rs.getString("listing_title"));
                m.put("priceAmount", rs.getString("price_amount"));
                m.put("hoursSinceUpdate", Math.round(rs.getDouble("hours_since_update")));
                return (Map<String, Object>) m;
            }).list();
    }

    private List<Map<String, Object>> loadLeadsWithoutResponse(UUID advertiserId) {
        return jdbc.sql("""
                SELECT ld.id, ld.contact_name, ld.stage, ld.source::text, ld.created_at,
                       l.title AS listing_title,
                       EXTRACT(EPOCH FROM (now() - ld.created_at))/3600 AS hours_waiting
                FROM properia.leads ld
                JOIN properia.listings l ON l.id = ld.listing_id
                WHERE l.advertiser_id = :adv
                  AND ld.stage = 'new'
                  AND ld.created_at < now() - interval '48 hours'
                ORDER BY ld.created_at ASC
                LIMIT 8
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("contactName", rs.getString("contact_name"));
                m.put("source", rs.getString("source"));
                m.put("listingTitle", rs.getString("listing_title"));
                m.put("hoursWaiting", Math.round(rs.getDouble("hours_waiting")));
                return (Map<String, Object>) m;
            }).list();
    }

    private List<Map<String, Object>> loadVisitsToConfirm(UUID advertiserId) {
        return jdbc.sql("""
                SELECT v.id, v.starts_at, v.status, v.mode::text,
                       l.title AS listing_title, l.street, l.city,
                       ld.contact_name AS lead_name
                FROM properia.visits v
                JOIN properia.listings l ON l.id = v.listing_id
                LEFT JOIN properia.leads ld ON ld.id = v.lead_id
                WHERE v.advertiser_id = :adv
                  AND v.status = 'requested'
                ORDER BY v.starts_at ASC
                LIMIT 10
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("startsAt", rs.getTimestamp("starts_at") != null ? rs.getTimestamp("starts_at").toInstant().toString() : null);
                m.put("mode", rs.getString("mode"));
                m.put("listingTitle", rs.getString("listing_title"));
                m.put("city", rs.getString("city"));
                m.put("leadName", rs.getString("lead_name"));
                return (Map<String, Object>) m;
            }).list();
    }

    private List<Map<String, Object>> loadListingPerformance(UUID advertiserId) {
        return jdbc.sql("""
                SELECT l.id, l.title, l.status, l.price_amount, l.property_type::text,
                       l.city,
                       COUNT(DISTINCT ld.id) AS total_leads,
                       COUNT(DISTINCT ld.id) FILTER (WHERE ld.created_at > now() - interval '30 days') AS leads_30d
                FROM properia.listings l
                LEFT JOIN properia.leads ld ON ld.listing_id = l.id
                WHERE l.advertiser_id = :adv
                  AND l.status IN ('published', 'draft')
                GROUP BY l.id
                ORDER BY leads_30d DESC, total_leads DESC
                LIMIT 10
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("title", rs.getString("title"));
                m.put("status", rs.getString("status"));
                m.put("priceAmount", rs.getString("price_amount"));
                m.put("propertyType", rs.getString("property_type"));
                m.put("city", rs.getString("city"));
                m.put("totalLeads", rs.getInt("total_leads"));
                m.put("leads30d", rs.getInt("leads_30d"));
                return (Map<String, Object>) m;
            }).list();
    }

    private List<Map<String, Object>> loadImportStatus(UUID advertiserId) {
        return jdbc.sql("""
                SELECT id, status, source_family::text, total_rows,
                       created_rows, merged_rows, rejected_rows, created_at
                FROM properia.crm_import_batches
                WHERE advertiser_id = :adv
                ORDER BY created_at DESC
                LIMIT 5
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("status", rs.getString("status"));
                m.put("sourceFamily", rs.getString("source_family"));
                m.put("totalRows", rs.getInt("total_rows"));
                m.put("createdRows", rs.getInt("created_rows"));
                m.put("mergedRows", rs.getInt("merged_rows"));
                m.put("rejectedRows", rs.getInt("rejected_rows"));
                m.put("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant().toString() : null);
                return (Map<String, Object>) m;
            }).list();
    }

    private List<Map<String, Object>> loadLeadsBySource(UUID advertiserId) {
        return jdbc.sql("""
                SELECT ld.source::text AS source, COUNT(*) AS total
                FROM properia.leads ld
                JOIN properia.listings l ON l.id = ld.listing_id
                WHERE l.advertiser_id = :adv
                  AND ld.created_at > now() - interval '30 days'
                GROUP BY ld.source
                ORDER BY total DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> Map.<String, Object>of(
                "source", rs.getString("source"),
                "total", rs.getInt("total")
            ))
            .list();
    }

    // ── Streaming endpoint ────────────────────────────────────────────────────

    @PostMapping("/api/advertiser/copiloto/stream")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {

        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        if (!openAiProps.isConfigured()) {
            throw new DomainException("AI_UNAVAILABLE", "O assistente IA não está configurado neste ambiente.", 503);
        }
        // Copiloto é Pro+ — impor no servidor (a IA consome tokens à nossa custa).
        planGuard.requireProFeatures(claims.activeAdvertiserId());

        var advertiserId = claims.activeAdvertiserId();
        var question = body.getOrDefault("question", "").toString().trim();
        if (question.isBlank()) {
            throw new DomainException("BAD_REQUEST", "Pergunta em falta.", 400);
        }

        @SuppressWarnings("unchecked")
        var history = body.containsKey("history")
            ? (List<Map<String, Object>>) body.get("history")
            : List.<Map<String, Object>>of();

        var context = buildContext(advertiserId);
        var startMs = System.currentTimeMillis();

        StreamingResponseBody responseBody = outputStream -> {
            var writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true);
            var accumulated = new StringBuilder();
            try {
                callOpenAIStream(context, question, history, chunk -> {
                    accumulated.append(chunk);
                    var event = Map.of("type", "token", "content", chunk);
                    writer.print("data: " + json.writeValueAsString(event) + "\n\n");
                    writer.flush();
                });

                String fullText = accumulated.toString();
                String cleanText = fullText;
                Object chartData = null;
                int chartStart = fullText.indexOf("```chart");
                if (chartStart >= 0) {
                    int chartEnd = fullText.indexOf("```", chartStart + 8);
                    if (chartEnd > chartStart) {
                        String chartJson = fullText.substring(chartStart + 8, chartEnd).trim();
                        cleanText = (fullText.substring(0, chartStart) + fullText.substring(chartEnd + 3)).trim();
                        try { chartData = json.readValue(chartJson, Object.class); } catch (Exception ignored) {}
                    }
                }

                if (!cleanText.equals(fullText)) {
                    var replaceEvent = Map.of("type", "replace", "content", cleanText);
                    writer.print("data: " + json.writeValueAsString(replaceEvent) + "\n\n");
                    writer.flush();
                }
                if (chartData != null) {
                    var chartEvent = Map.of("type", "chart", "data", chartData);
                    writer.print("data: " + json.writeValueAsString(chartEvent) + "\n\n");
                    writer.flush();
                }

                var durationMs = System.currentTimeMillis() - startMs;
                var doneEvent = Map.of("type", "done", "durationMs", durationMs);
                writer.print("data: " + json.writeValueAsString(doneEvent) + "\n\n");
                writer.flush();

            } catch (Exception e) {
                try {
                    var msg = (e instanceof DomainException de) ? de.getMessage()
                        : "Não foi possível obter resposta. Tenta novamente.";
                    var errorEvent = Map.of("type", "error", "message", msg);
                    writer.print("data: " + json.writeValueAsString(errorEvent) + "\n\n");
                    writer.flush();
                } catch (Exception ignored) {}
            }
        };

        return ResponseEntity.ok()
            .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(responseBody);
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    private void callOpenAIStream(
            Map<String, Object> context, String question,
            List<Map<String, Object>> history,
            ThrowingConsumer<String> onChunk) throws Exception {

        var contextJson = json.writeValueAsString(context);
        var today = java.time.LocalDate.now().toString();
        var systemContent = String.format(SYSTEM_PROMPT, today, contextJson);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", systemContent));
        for (var h : history) {
            var role = h.getOrDefault("role", "user").toString();
            var content = h.getOrDefault("content", "").toString();
            if (!content.isBlank() && (role.equals("user") || role.equals("assistant"))) {
                messages.add(Map.of("role", role, "content", content));
            }
        }
        messages.add(Map.of("role", "user", "content", question));

        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", "gpt-4.1-mini");
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 1200);
        requestBody.put("temperature", 0.4);
        requestBody.put("stream", true);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(openAiProps.getUrl() + "/chat/completions"))
            .header("Authorization", "Bearer " + openAiProps.getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
            .timeout(Duration.ofSeconds(60))
            .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new DomainException("AI_ERROR",
                "Erro na API de IA (status " + response.statusCode() + "). Tenta novamente.", 503);
        }

        response.body().forEach(line -> {
            if (!line.startsWith("data: ")) return;
            var data = line.substring(6).trim();
            if (data.equals("[DONE]")) return;
            try {
                var node = json.readTree(data);
                var content = node.path("choices").path(0).path("delta").path("content").asText(null);
                if (content != null && !content.isEmpty()) {
                    onChunk.accept(content);
                }
            } catch (Exception ignored) {}
        });
    }

    // ── OpenAI call ───────────────────────────────────────────────────────────

    private String callOpenAI(Map<String, Object> context, String question, List<Map<String, Object>> history) {
        try {
            var contextJson = json.writeValueAsString(context);
            var today = java.time.LocalDate.now().toString();
            var systemContent = String.format(SYSTEM_PROMPT, today, contextJson);

            var messages = new ArrayList<Map<String, Object>>();
            messages.add(Map.of("role", "system", "content", systemContent));
            for (var h : history) {
                var role = h.getOrDefault("role", "user").toString();
                var content = h.getOrDefault("content", "").toString();
                if (!content.isBlank() && (role.equals("user") || role.equals("assistant"))) {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
            messages.add(Map.of("role", "user", "content", question));

            var requestBody = Map.of(
                "model", "gpt-4.1-mini",
                "messages", messages,
                "max_tokens", 1200,
                "temperature", 0.4
            );

            var request = HttpRequest.newBuilder()
                .uri(URI.create(openAiProps.getUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + openAiProps.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
                .timeout(Duration.ofSeconds(30))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DomainException("AI_ERROR",
                    "Erro na API de IA (status " + response.statusCode() + "). Tenta novamente.", 503);
            }

            var node = json.readTree(response.body());
            return node.path("choices").path(0).path("message").path("content").asText("Não foi possível obter resposta.");

        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException("AI_ERROR", "Não foi possível contactar o assistente IA: " + e.getMessage(), 503);
        }
    }
}
