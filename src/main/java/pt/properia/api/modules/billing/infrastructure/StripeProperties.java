package pt.properia.api.modules.billing.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "properia.stripe")
public class StripeProperties {

    private String secretKey = "";
    private String webhookSecret = "";
    private String creditsWebhookSecret = "";
    private String billingProvider = "fake";

    // Price IDs — set via env vars
    private String priceIdProMonthly = "";
    private String priceIdProAnnual = "";
    private String priceIdBusinessMonthly = "";
    private String priceIdBusinessAnnual = "";

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getCreditsWebhookSecret() { return creditsWebhookSecret; }
    public void setCreditsWebhookSecret(String creditsWebhookSecret) { this.creditsWebhookSecret = creditsWebhookSecret; }

    public String getBillingProvider() { return billingProvider; }
    public void setBillingProvider(String billingProvider) { this.billingProvider = billingProvider; }

    public String getPriceIdProMonthly() { return priceIdProMonthly; }
    public void setPriceIdProMonthly(String v) { this.priceIdProMonthly = v; }

    public String getPriceIdProAnnual() { return priceIdProAnnual; }
    public void setPriceIdProAnnual(String v) { this.priceIdProAnnual = v; }

    public String getPriceIdBusinessMonthly() { return priceIdBusinessMonthly; }
    public void setPriceIdBusinessMonthly(String v) { this.priceIdBusinessMonthly = v; }

    public String getPriceIdBusinessAnnual() { return priceIdBusinessAnnual; }
    public void setPriceIdBusinessAnnual(String v) { this.priceIdBusinessAnnual = v; }

    public boolean isFake() { return "fake".equals(billingProvider); }

    public String resolvePriceId(String planCode, String billingCycle) {
        boolean isAnnual = "annual".equals(billingCycle);
        return switch (planCode) {
            case "pro" -> isAnnual ? priceIdProAnnual : priceIdProMonthly;
            case "business" -> isAnnual ? priceIdBusinessAnnual : priceIdBusinessMonthly;
            default -> throw new IllegalArgumentException("Plano desconhecido: " + planCode);
        };
    }
}
