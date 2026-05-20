package pt.properia.api.shared.infrastructure.web.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT configuration properties bound from application.yml (properia.jwt.*).
 */
@Component
@ConfigurationProperties(prefix = "properia.jwt")
public class JwtProperties {

    private String secret;
    private long ttlSeconds = 28800; // 8 hours
    private String issuer = "properia";
    private String audience = "properia-web";
    private String cookieName = "properia_session";
    private String cookieDomain = "";
    private boolean cookieSecure = false;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public String getCookieName() { return cookieName; }
    public void setCookieName(String cookieName) { this.cookieName = cookieName; }

    public String getCookieDomain() { return cookieDomain; }
    public void setCookieDomain(String cookieDomain) { this.cookieDomain = cookieDomain; }

    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
}
