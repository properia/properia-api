package pt.properia.api.modules.geocoding.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "properia.geocoding")
public class GeocodingProperties {

    private String url = "https://nominatim.openstreetmap.org/search";
    private int timeoutMs = 7000;
    private String userAgent = "ProperiaApi/1.0";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
