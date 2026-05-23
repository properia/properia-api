package pt.properia.api.modules.enrichment.vision.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.enrichment.vision.infrastructure.OpenAIProperties;
import pt.properia.api.shared.domain.DomainException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class VisionService {

    private static final String SYSTEM_PROMPT = """
        You are a real estate photography analyst.
        Analyze the provided property images and return ONLY a valid JSON object (no markdown, no explanation) with this exact structure:
        {
          "conditionAi": "used_good",
          "conditionConfidence": 0.85,
          "qualityScore": 7.5,
          "stylesDetected": ["moderno"],
          "stylePrimary": "moderno",
          "styleSecondary": null,
          "furnitureDetected": ["sofa"],
          "roomsDetected": ["sala"],
          "materialsDetected": ["madeira"],
          "signalsDetected": ["luz natural"],
          "lightQualityScore": 8.0,
          "spaciousnessScore": 7.0,
          "layoutQualityScore": 7.5,
          "premiumScore": 6.5,
          "familyFriendlyScore": 8.0,
          "homeOfficeScore": 5.0,
          "luxuryScore": 5.0,
          "needsHumanReview": false,
          "sellingPoints": ["boa luz natural"],
          "buyerProfiles": ["family"],
          "buyerProfilePrimary": "family",
          "coherenceFlags": [],
          "photoRankings": []
        }
        conditionAi values: new, remodeled, used_good, used_regular, to_renovate, shell_core, under_construction
        stylesDetected values: moderno, classico, industrial, rustico, mediterraneo, contemporaneo, minimalista
        buyerProfiles values: young_professional, couple, family, retiree, investor, remote_worker, downsizer
        Scores are 0-10 (decimal allowed). conditionConfidence is 0.0-1.0.
        Return ONLY the JSON object. No markdown. No extra text.
        """;

    private final OpenAIProperties props;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final HttpClient http;

    public VisionService(OpenAIProperties props, JdbcClient jdbc, ObjectMapper json) {
        this.props = props;
        this.jdbc = jdbc;
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public Map<String, Object> analyzeListingImages(UUID listingId) {
        if (!props.isConfigured()) {
            throw new DomainException("VISION_UNAVAILABLE",
                "A análise por IA não está configurada neste ambiente.", 503);
        }

        var imageUrls = getListingImageUrls(listingId);
        if (imageUrls.isEmpty()) {
            throw new DomainException("NO_MEDIA",
                "O anúncio não tem imagens para a análise IA. Adiciona pelo menos uma foto.", 422);
        }

        var limited = imageUrls.subList(0, Math.min(imageUrls.size(), props.getVisionMaxImages()));
        var visionSummary = callOpenAIVision(limited, listingId);
        saveEnrichmentResult(listingId, visionSummary);

        var result = new LinkedHashMap<String, Object>();
        result.put("listingId", listingId.toString());
        result.put("attempts", 1);
        result.put("completed", true);
        result.put("visionSummary", visionSummary);
        return result;
    }

    private List<String> getListingImageUrls(UUID listingId) {
        return jdbc.sql("""
                SELECT url FROM properia.listing_media
                WHERE listing_id = :id AND media_type = 'image'
                ORDER BY sort_order ASC
                LIMIT :max
                """)
            .param("id", listingId)
            .param("max", props.getVisionMaxImages())
            .query((rs, n) -> rs.getString("url"))
            .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callOpenAIVision(List<String> imageUrls, UUID listingId) {
        try {
            // Build message content with text + images
            var content = new ArrayList<Map<String, Object>>();
            content.add(Map.of("type", "text", "text",
                "Analisa estas imagens de um imóvel português para venda ou arrendamento."));
            for (var url : imageUrls) {
                content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", url, "detail", "low")
                ));
            }

            var requestBody = Map.of(
                "model", props.getVisionModel(),
                "messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", content)
                ),
                "max_tokens", 1500,
                "temperature", 0.2
            );

            var requestJson = json.writeValueAsString(requestBody);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(props.getUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofMillis(props.getVisionTimeoutMs()))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DomainException("VISION_API_ERROR",
                    "Erro na API de visão IA (status " + response.statusCode() + "). Tenta novamente.", 503);
            }

            var responseNode = json.readTree(response.body());
            var content0 = responseNode.path("choices").path(0).path("message").path("content").asText();

            // Parse the JSON returned by OpenAI
            var rawJson = content0.trim();
            if (rawJson.startsWith("```")) {
                rawJson = rawJson.replaceAll("```json?", "").replaceAll("```", "").trim();
            }

            var parsed = json.readTree(rawJson);
            return buildVisionSummary(parsed, listingId);

        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException("VISION_ERROR",
                "Não foi possível processar as imagens com IA: " + e.getMessage(), 503);
        }
    }

    private Map<String, Object> buildVisionSummary(JsonNode n, UUID listingId) {
        var s = new LinkedHashMap<String, Object>();
        s.put("version", 1);
        s.put("provider", "openai");
        s.put("model", props.getVisionModel());
        s.put("processedAt", Instant.now().toString());
        s.put("stylesDetected", toStringList(n.path("stylesDetected")));
        s.put("stylePrimary", textOrNull(n, "stylePrimary"));
        s.put("styleSecondary", textOrNull(n, "styleSecondary"));
        s.put("conditionAi", textOrNull(n, "conditionAi"));
        s.put("conditionConfidence", doubleOrNull(n, "conditionConfidence"));
        s.put("qualityScore", doubleOrNull(n, "qualityScore"));
        s.put("furnitureDetected", toStringList(n.path("furnitureDetected")));
        s.put("roomsDetected", toStringList(n.path("roomsDetected")));
        s.put("materialsDetected", toStringList(n.path("materialsDetected")));
        s.put("signalsDetected", toStringList(n.path("signalsDetected")));
        s.put("lightQualityScore", doubleOrNull(n, "lightQualityScore"));
        s.put("spaciousnessScore", doubleOrNull(n, "spaciousnessScore"));
        s.put("layoutQualityScore", doubleOrNull(n, "layoutQualityScore"));
        s.put("premiumScore", doubleOrNull(n, "premiumScore"));
        s.put("familyFriendlyScore", doubleOrNull(n, "familyFriendlyScore"));
        s.put("homeOfficeScore", doubleOrNull(n, "homeOfficeScore"));
        s.put("luxuryScore", doubleOrNull(n, "luxuryScore"));
        s.put("needsHumanReview", n.path("needsHumanReview").asBoolean(false));
        s.put("sellingPoints", toStringList(n.path("sellingPoints")));
        s.put("buyerProfiles", toStringList(n.path("buyerProfiles")));
        s.put("buyerProfilePrimary", textOrNull(n, "buyerProfilePrimary"));
        s.put("coherenceFlags", List.of());
        s.put("photoRankings", List.of());
        return s;
    }

    private void saveEnrichmentResult(UUID listingId, Map<String, Object> summary) {
        try {
            var rawJson = json.writeValueAsString(summary);
            var conditionAi = summary.get("conditionAi");
            jdbc.sql("""
                    INSERT INTO properia.listing_ai_vision
                      (listing_id, version, provider, model, processed_at,
                       styles_detected, style_primary, style_secondary,
                       condition_confidence, quality_score,
                       furniture_detected, rooms_detected, materials_detected, signals_detected,
                       light_quality_score, spaciousness_score, layout_quality_score,
                       premium_score, family_friendly_score, home_office_score, luxury_score,
                       needs_human_review, raw_response, created_at, updated_at)
                    VALUES
                      (:lid, 1, :provider, :model, now(),
                       :styles::jsonb, :stylePrimary, :styleSecondary,
                       :condConf, :quality,
                       :furniture::jsonb, :rooms::jsonb, :materials::jsonb, :signals::jsonb,
                       :lightQ, :spacious, :layoutQ,
                       :premium, :family, :homeOffice, :luxury,
                       :humanReview, :raw::jsonb, now(), now())
                    ON CONFLICT (listing_id) DO UPDATE SET
                      version = EXCLUDED.version,
                      provider = EXCLUDED.provider,
                      model = EXCLUDED.model,
                      processed_at = now(),
                      styles_detected = EXCLUDED.styles_detected,
                      style_primary = EXCLUDED.style_primary,
                      style_secondary = EXCLUDED.style_secondary,
                      condition_confidence = EXCLUDED.condition_confidence,
                      quality_score = EXCLUDED.quality_score,
                      furniture_detected = EXCLUDED.furniture_detected,
                      rooms_detected = EXCLUDED.rooms_detected,
                      materials_detected = EXCLUDED.materials_detected,
                      signals_detected = EXCLUDED.signals_detected,
                      light_quality_score = EXCLUDED.light_quality_score,
                      spaciousness_score = EXCLUDED.spaciousness_score,
                      layout_quality_score = EXCLUDED.layout_quality_score,
                      premium_score = EXCLUDED.premium_score,
                      family_friendly_score = EXCLUDED.family_friendly_score,
                      home_office_score = EXCLUDED.home_office_score,
                      luxury_score = EXCLUDED.luxury_score,
                      needs_human_review = EXCLUDED.needs_human_review,
                      raw_response = EXCLUDED.raw_response,
                      updated_at = now()
                    """)
                .param("lid", listingId)
                .param("provider", "openai")
                .param("model", props.getVisionModel())
                .param("styles", json.writeValueAsString(summary.getOrDefault("stylesDetected", List.of())))
                .param("stylePrimary", summary.get("stylePrimary"))
                .param("styleSecondary", summary.get("styleSecondary"))
                .param("condConf", summary.get("conditionConfidence"))
                .param("quality", summary.get("qualityScore"))
                .param("furniture", json.writeValueAsString(summary.getOrDefault("furnitureDetected", List.of())))
                .param("rooms", json.writeValueAsString(summary.getOrDefault("roomsDetected", List.of())))
                .param("materials", json.writeValueAsString(summary.getOrDefault("materialsDetected", List.of())))
                .param("signals", json.writeValueAsString(summary.getOrDefault("signalsDetected", List.of())))
                .param("lightQ", summary.get("lightQualityScore"))
                .param("spacious", summary.get("spaciousnessScore"))
                .param("layoutQ", summary.get("layoutQualityScore"))
                .param("premium", summary.get("premiumScore"))
                .param("family", summary.get("familyFriendlyScore"))
                .param("homeOffice", summary.get("homeOfficeScore"))
                .param("luxury", summary.get("luxuryScore"))
                .param("humanReview", Boolean.TRUE.equals(summary.get("needsHumanReview")))
                .param("raw", rawJson)
                .update();

            // Also update job status
            if (conditionAi != null) {
                jdbc.sql("""
                        UPDATE properia.job_executions SET status = 'completed', updated_at = now()
                        WHERE entity_id = :eid AND job_type = 'listing_vision_enrichment'
                          AND status = 'queued'
                        """)
                    .param("eid", listingId.toString())
                    .update();
            }
        } catch (Exception ignored) {
            // Non-fatal: enrichment result save failure shouldn't block the response
        }
    }

    private String textOrNull(JsonNode n, String field) {
        var v = n.path(field);
        if (v.isNull() || v.isMissingNode()) return null;
        var s = v.asText();
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private Double doubleOrNull(JsonNode n, String field) {
        var v = n.path(field);
        if (v.isNull() || v.isMissingNode()) return null;
        return v.asDouble();
    }

    private List<String> toStringList(JsonNode n) {
        if (!n.isArray()) return List.of();
        var list = new ArrayList<String>();
        for (var item : n) {
            if (!item.isNull()) list.add(item.asText());
        }
        return list;
    }
}
