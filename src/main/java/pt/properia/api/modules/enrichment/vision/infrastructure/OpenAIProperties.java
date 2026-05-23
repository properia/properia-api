package pt.properia.api.modules.enrichment.vision.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "properia.openai")
public class OpenAIProperties {

    private String url = "https://api.openai.com/v1";
    private String apiKey = "";
    private String visionModel = "gpt-4.1-mini";
    private long visionTimeoutMs = 20000;
    private int visionMaxImages = 6;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

    public long getVisionTimeoutMs() { return visionTimeoutMs; }
    public void setVisionTimeoutMs(long visionTimeoutMs) { this.visionTimeoutMs = visionTimeoutMs; }

    public int getVisionMaxImages() { return visionMaxImages; }
    public void setVisionMaxImages(int visionMaxImages) { this.visionMaxImages = visionMaxImages; }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
