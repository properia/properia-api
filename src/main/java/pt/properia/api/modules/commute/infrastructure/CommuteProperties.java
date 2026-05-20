package pt.properia.api.modules.commute.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "properia.openroute")
public class CommuteProperties {

    private String url = "https://api.openrouteservice.org/v2/matrix/driving-car";
    private String apiKey = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getDirectionsBase() {
        // Derive directions base URL from the matrix URL
        var base = url.replaceAll("/v2/matrix/.*$", "");
        return base.startsWith("http") ? base : "https://api.openrouteservice.org";
    }
}
