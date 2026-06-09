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
import java.util.List;
import java.util.Map;

/**
 * Client for Kling AI v2.1 via fal.ai queue API.
 * Generates property showcase videos from photos using minimal camera movement.
 *
 * cfg_scale is kept low (0.3) so the model stays close to the source image and
 * produces a subtle depth-parallax effect instead of panning/rotating outside
 * the visible frame. This avoids AI hallucination of rooms or spaces that do not
 * exist in the original photo.
 *
 * Docs: https://fal.ai/models/fal-ai/kling-video/v2.1/pro/image-to-video/api
 */
@Service
public class KlingService {

    private static final Logger log = LoggerFactory.getLogger(KlingService.class);

    private static final String MODEL      = "fal-ai/kling-video/v2.1/pro/image-to-video";
    private static final String QUEUE_BASE = "https://queue.fal.run/" + MODEL;

    private static final int POLL_INTERVAL_MS = 10_000; // 10s between polls
    private static final int POLL_MAX_ATTEMPTS = 36;    // 6 min max

    @Value("${properia.fal.api-key:}")
    private String apiKey;

    private final HttpClient http;
    private final ObjectMapper json;

    public KlingService(ObjectMapper json) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.json = json;
    }

    public record SubmitResult(String requestId) {}
    public record GenerationStatus(String status, String videoUrl) {}

    /**
     * Submits a single Kling image-to-video job for one photo.
     * The model generates a cinematic 5s clip with camera movement through that scene.
     */
    public SubmitResult submitGeneration(List<String> photoUrls) {
        return submitSingleClip(photoUrls.get(0));
    }

    /**
     * Submits N jobs in parallel (one per photo) and returns all request IDs.
     * Max 5 photos to keep cost reasonable.
     */
    public List<SubmitResult> submitAllClips(List<String> photoUrls) {
        return photoUrls.stream()
            .limit(5)
            .map(this::submitSingleClip)
            .toList();
    }

    public SubmitResult submitSingleClip(String imageUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("FAL_API_KEY não configurado.");
        }

        var body = Map.of(
            "prompt",          buildPrompt(),
            "negative_prompt", buildNegativePrompt(),
            "image_url",       imageUrl,
            "duration",        "5",
            "aspect_ratio",    "16:9",
            "cfg_scale",       0.3   // low motion intensity — keeps camera close to source image
        );

        try {
            var requestBody = json.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(QUEUE_BASE))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Key " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Kling submit failed: {} {}", response.statusCode(), response.body());
                throw new RuntimeException("fal.ai retornou HTTP " + response.statusCode() + ": " + response.body());
            }

            @SuppressWarnings("unchecked")
            var parsed    = (Map<String, Object>) json.readValue(response.body(), Map.class);
            var requestId = (String) parsed.get("request_id");
            log.info("Kling generation submitted: requestId={}", requestId);
            return new SubmitResult(requestId);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Falha ao submeter geração Kling: " + e.getMessage(), e);
        }
    }

    /**
     * Polls fal.ai until the generation completes or times out.
     * Returns the video URL when done.
     */
    public String pollUntilComplete(String requestId) {
        for (int i = 0; i < POLL_MAX_ATTEMPTS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Tour generation interrupted.");
            }

            var status = getStatus(requestId);
            if (status == null) continue;

            log.debug("Kling requestId={} status={}", requestId, status.status());

            switch (status.status()) {
                case "COMPLETED" -> { return status.videoUrl(); }
                case "FAILED"    -> throw new RuntimeException("Kling generation failed for requestId=" + requestId);
            }
        }

        throw new RuntimeException("Kling generation timed out after " + (POLL_MAX_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s.");
    }

    public GenerationStatus getStatus(String requestId) {
        if (apiKey == null || apiKey.isBlank()) return null;
        try {
            // fal.ai queue status — uses the generic /fal-ai/queue/ path, not model-specific
            var statusUrl = "https://queue.fal.run/fal-ai/queue/requests/" + requestId + "/status";
            var statusRequest = HttpRequest.newBuilder()
                .uri(URI.create(statusUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

            var statusResponse = http.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            if (statusResponse.body() == null || statusResponse.body().isBlank()) return null;

            @SuppressWarnings("unchecked")
            var statusParsed = (Map<String, Object>) json.readValue(statusResponse.body(), Map.class);
            var state = (String) statusParsed.get("status");

            if (!"COMPLETED".equals(state)) {
                return new GenerationStatus(state != null ? state : "IN_QUEUE", null);
            }

            // Fetch the result from response_url (also generic queue path)
            var responseUrl = (String) statusParsed.get("response_url");
            if (responseUrl == null) {
                responseUrl = "https://queue.fal.run/fal-ai/queue/requests/" + requestId;
            }

            var resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

            var resultResponse = http.send(resultRequest, HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) json.readValue(resultResponse.body(), Map.class);

            @SuppressWarnings("unchecked")
            var video    = (Map<String, Object>) result.get("video");
            var videoUrl = video != null ? (String) video.get("url") : null;

            return new GenerationStatus("COMPLETED", videoUrl);

        } catch (Exception e) {
            log.warn("Erro ao consultar status Kling requestId={}: {}", requestId, e.getMessage());
            return null;
        }
    }

    private String buildPrompt() {
        // Goal: animate depth and atmosphere WITHIN the visible frame.
        // "Locked off" and "static camera" instruct the model to avoid exploring
        // areas outside the photo. "Parallax depth" gives the AI the goal of
        // adding perceived depth without requiring new pixel invention.
        return "Real estate photography. Locked-off static camera. "
             + "Subtle atmospheric depth effect and gentle parallax within the visible frame. "
             + "Soft natural lighting variation. "
             + "Do not move the camera. Do not reveal any space outside the original photo. "
             + "Professional architectural photography style.";
    }

    private String buildNegativePrompt() {
        return "camera movement, panning, rotation, tilt, zoom out, dolly, tracking shot, "
             + "new rooms, hallucination, invented spaces, areas outside original photo, "
             + "lateral movement, forward movement, camera turning, exploring unseen areas, "
             + "blur, distortion, low quality, artifacts";
    }
}
