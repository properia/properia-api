package pt.properia.api.modules.listings.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for the Shotstack v1 Render API.
 * Creates a Ken Burns property-tour video from a list of photo URLs.
 * Docs: https://shotstack.io/docs/api/
 */
@Service
public class ShotstackService {

    private static final Logger log = LoggerFactory.getLogger(ShotstackService.class);

    @Value("${properia.shotstack.api-url:https://api.shotstack.io/stage/render}")
    private String shotstackApiUrl;
    private static final int    CLIP_DURATION  = 4;   // seconds per photo
    private static final int    MAX_PHOTOS     = 20;

    @Value("${properia.shotstack.api-key:}")
    private String apiKey;

    private final HttpClient http;
    private final ObjectMapper json;

    public ShotstackService(ObjectMapper json) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.json = json;
    }

    public record SubmitResult(String renderId) {}

    /**
     * Submits a render job to Shotstack and returns the render ID.
     * The video is generated asynchronously; poll /status or wait for webhook.
     *
     * @param photoUrls  ordered list of photo URLs (max 20)
     * @param webhookUrl full URL Shotstack will POST to when done (nullable)
     */
    public SubmitResult submitRender(List<String> photoUrls, String webhookUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("SHOTSTACK_API_KEY não configurado.");
        }

        var clips = photoUrls.stream()
            .limit(MAX_PHOTOS)
            .map(url -> Map.of(
                "asset", Map.of("type", "image", "src", url),
                "start", photoUrls.indexOf(url) * CLIP_DURATION,
                "length", CLIP_DURATION,
                "effect", pickEffect(photoUrls.indexOf(url)),
                "transition", Map.of("in", "fade", "out", "fade")
            ))
            .toList();

        var timeline = Map.of(
            "background", "#000000",
            "tracks", List.of(Map.of("clips", clips))
        );

        var output = Map.of(
            "format", "mp4",
            "resolution", "hd",
            "fps", 25,
            "quality", "medium"
        );

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("timeline", timeline);
        body.put("output", output);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            body.put("callback", webhookUrl);
        }

        try {
            var requestBody = json.writeValueAsString(body);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(shotstackApiUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", apiKey)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Shotstack submit failed: {} {}", response.statusCode(), response.body());
                throw new RuntimeException("Shotstack retornou HTTP " + response.statusCode());
            }

            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            var responseBlock = (Map<String, Object>) parsed.get("response");
            var renderId = (String) responseBlock.get("id");

            return new SubmitResult(renderId);

        } catch (Exception e) {
            log.error("Erro ao submeter render Shotstack", e);
            throw new RuntimeException("Falha ao iniciar geração do tour virtual: " + e.getMessage(), e);
        }
    }

    /**
     * Polls the render status from Shotstack.
     * Returns null if the key is not configured (graceful degradation).
     */
    public record RenderStatus(String status, String url) {}

    public RenderStatus getRenderStatus(String renderId) {
        if (apiKey == null || apiKey.isBlank()) return null;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(shotstackApiUrl + "/" + renderId))
                .timeout(Duration.ofSeconds(10))
                .header("x-api-key", apiKey)
                .GET()
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            var parsed    = (Map<String, Object>) json.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            var respBlock = (Map<String, Object>) parsed.get("response");
            var status    = (String) respBlock.get("status");
            var url       = (String) respBlock.get("url");

            return new RenderStatus(status, url);

        } catch (Exception e) {
            log.warn("Erro ao consultar status Shotstack render {}: {}", renderId, e.getMessage());
            return null;
        }
    }

    // Alternates between zoomIn and zoomOut for a dynamic look
    private String pickEffect(int index) {
        return (index % 2 == 0) ? "zoomIn" : "zoomOut";
    }
}
