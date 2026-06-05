package pt.properia.api.modules.listings.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Luma AI Dream Machine API.
 * Generates cinematic property tour videos from photos using AI camera motion.
 * Docs: https://docs.lumalabs.ai/docs/video-generation
 */
@Service
public class LumaAIService {

    private static final Logger log = LoggerFactory.getLogger(LumaAIService.class);

    private static final String BASE_URL     = "https://api.lumalabs.ai/dream-machine/v1/generations";
    private static final int    POLL_MAX_SEC = 300; // 5 min max wait
    private static final int    POLL_INTERVAL_MS = 8000;

    @Value("${properia.luma.api-key:}")
    private String apiKey;

    private final HttpClient http;
    private final ObjectMapper json;

    public LumaAIService(ObjectMapper json) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.json = json;
    }

    public record GenerateResult(String generationId) {}
    public record GenerationStatus(String state, String videoUrl) {}

    /**
     * Submits a Dream Machine generation from property photos.
     *
     * Uses first photo as frame0 (start scene) and last photo as frame1 (end scene).
     * Luma AI creates a smooth cinematic camera movement interpolating between them.
     * For more than 2 photos, we pick a diverse spread (first, middle, last).
     */
    public GenerateResult submitGeneration(List<String> photoUrls) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LUMA_API_KEY não configurado.");
        }
        if (photoUrls.isEmpty()) {
            throw new IllegalArgumentException("Sem fotos para gerar tour.");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", buildPrompt());
        body.put("aspect_ratio", "16:9");
        body.put("loop", false);

        // Use 2 keyframes: first and last photo for best camera motion
        var keyframes = new LinkedHashMap<String, Object>();
        keyframes.put("frame0", Map.of("type", "image", "url", photoUrls.get(0)));
        if (photoUrls.size() > 1) {
            keyframes.put("frame1", Map.of("type", "image", "url", photoUrls.get(photoUrls.size() - 1)));
        }
        body.put("keyframes", keyframes);

        try {
            var requestBody = json.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "luma-api-key " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Luma AI submit failed: {} {}", response.statusCode(), response.body());
                throw new RuntimeException("Luma AI retornou HTTP " + response.statusCode() + ": " + response.body());
            }

            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
            var id = (String) parsed.get("id");
            log.info("Luma AI generation submitted: {}", id);
            return new GenerateResult(id);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Falha ao submeter geração Luma AI: " + e.getMessage(), e);
        }
    }

    /**
     * Polls Luma AI until the generation completes or times out.
     * Returns the video URL when done, or throws on failure/timeout.
     */
    public String pollUntilComplete(String generationId) {
        var maxAttempts = (POLL_MAX_SEC * 1000) / POLL_INTERVAL_MS;

        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Tour generation interrupted.");
            }

            var status = getStatus(generationId);
            if (status == null) continue;

            switch (status.state()) {
                case "completed" -> { return status.videoUrl(); }
                case "failed"    -> throw new RuntimeException("Luma AI generation failed.");
                default          -> log.debug("Luma AI generation {} state={}", generationId, status.state());
            }
        }

        throw new RuntimeException("Luma AI generation timed out after " + POLL_MAX_SEC + "s.");
    }

    public GenerationStatus getStatus(String generationId) {
        if (apiKey == null || apiKey.isBlank()) return null;
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/" + generationId))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "luma-api-key " + apiKey)
                .GET()
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) json.readValue(response.body(), Map.class);
            var state  = (String) parsed.get("state");

            @SuppressWarnings("unchecked")
            var assets   = (Map<String, Object>) parsed.get("assets");
            var videoUrl = assets != null ? (String) assets.get("video") : null;

            return new GenerationStatus(state != null ? state : "pending", videoUrl);

        } catch (Exception e) {
            log.warn("Erro ao consultar status Luma AI {}: {}", generationId, e.getMessage());
            return null;
        }
    }

    private String buildPrompt() {
        return "Smooth cinematic camera walkthrough of a luxury real estate property interior. "
             + "Professional real estate photography style. "
             + "Slow elegant camera movement revealing the space. "
             + "High quality, warm natural lighting.";
    }
}
