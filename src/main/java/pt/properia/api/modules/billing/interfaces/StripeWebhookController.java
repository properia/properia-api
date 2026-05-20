package pt.properia.api.modules.billing.interfaces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.billing.application.BillingService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final BillingService billingService;
    private final JdbcClient jdbc;

    @Value("${properia.webhooks.email-token:}")
    private String emailWebhookToken;

    public StripeWebhookController(BillingService billingService, JdbcClient jdbc) {
        this.billingService = billingService;
        this.jdbc = jdbc;
    }

    @PostMapping("/stripe")
    public ResponseEntity<?> handleStripe(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false, defaultValue = "") String signature) {
        billingService.handleWebhook(payload, signature);
        return ResponseEntity.ok(Map.of("received", true));
    }

    /**
     * POST /api/webhooks/email/inbound
     * Receives Postmark inbound emails for lead ingestion.
     * Extracts the advertiser token from the recipient address and queues the import.
     */
    @PostMapping("/email/inbound")
    public ResponseEntity<?> handleEmailInbound(
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Postmark-Token", required = false, defaultValue = "") String pmToken) {
        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty payload."));
        }
        try {
            // Extract inbound token from recipient address (format: leads-{token}@leads.properia.pt)
            @SuppressWarnings("unchecked")
            var toFull = payload.get("ToFull") instanceof java.util.List<?> l ? (java.util.List<Map<String, Object>>) l : null;
            var inboundToken = extractInboundToken(toFull);
            if (inboundToken == null) {
                log.warn("email_inbound.no_token: no matching recipient found");
                return ResponseEntity.ok(Map.of("ok", true, "skipped", "no_token"));
            }

            // Find advertiser by inbound token
            var advertiser = jdbc.sql("""
                    SELECT id FROM properia.advertisers WHERE inbound_email_token = :token
                    """).param("token", inboundToken)
                .query((rs, n) -> rs.getString("id"))
                .optional();

            if (advertiser.isEmpty()) {
                log.warn("email_inbound.unknown_token: {}", inboundToken.substring(0, 8));
                return ResponseEntity.ok(Map.of("ok", true, "skipped", "unknown_token"));
            }

            var advertiserId = UUID.fromString(advertiser.get());
            var subject = payload.getOrDefault("Subject", "").toString();
            var from = payload.getOrDefault("From", "").toString();
            var batchId = UUID.randomUUID();

            // Create import batch for async processing
            jdbc.sql("""
                    INSERT INTO properia.crm_import_batches
                      (id, advertiser_id, source_type, status, raw_payload, created_at, updated_at)
                    VALUES (:id, :adv, 'email_inbound', 'pending', :payload::jsonb, now(), now())
                    """)
                .param("id", batchId)
                .param("adv", advertiserId)
                .param("payload", "{\"subject\":\"" + escapeJson(subject) + "\",\"from\":\"" + escapeJson(from) + "\"}")
                .update();

            log.info("email_inbound.queued: batch={} advertiser={}", batchId, advertiserId);
        } catch (Exception e) {
            log.error("email_inbound.error", e);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * POST /api/webhooks/portals/{portal}
     * Receives lead webhooks from real estate portals (idealista, imovirtual, etc.)
     */
    @PostMapping("/portals/{portal}")
    public ResponseEntity<?> handlePortalWebhook(
            @PathVariable String portal,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestParam(required = false) String token) {

        var supported = java.util.Set.of("idealista", "imovirtual", "casa_sapo", "site_proprio", "generic");
        if (!supported.contains(portal)) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown portal."));
        }
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing token."));
        }
        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON body."));
        }

        try {
            // Look up advertiser by portal webhook token
            var advertiser = jdbc.sql("""
                    SELECT id FROM properia.advertisers WHERE webhook_token = :token
                    """).param("token", token)
                .query((rs, n) -> rs.getString("id"))
                .optional();

            if (advertiser.isEmpty()) {
                log.warn("portal_webhook.unknown_token: portal={} token={}", portal, token.substring(0, Math.min(8, token.length())));
                return ResponseEntity.ok(Map.of("ok", true, "ingested", false));
            }

            var advertiserId = UUID.fromString(advertiser.get());
            var batchId = UUID.randomUUID();

            jdbc.sql("""
                    INSERT INTO properia.crm_import_batches
                      (id, advertiser_id, source_type, status, raw_payload, created_at, updated_at)
                    VALUES (:id, :adv, :portal, 'pending', :payload::jsonb, now(), now())
                    """)
                .param("id", batchId)
                .param("adv", advertiserId)
                .param("portal", "portal_" + portal)
                .param("payload", "{\"portal\":\"" + portal + "\"}")
                .update();

            log.info("portal_webhook.queued: portal={} batch={} advertiser={}", portal, batchId, advertiserId);
            return ResponseEntity.ok(Map.of("ok", true, "ingested", true));
        } catch (Exception e) {
            log.error("portal_webhook.error: portal={}", portal, e);
            return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
        }
    }

    private String extractInboundToken(java.util.List<Map<String, Object>> toFull) {
        if (toFull == null) return null;
        var inboundDomain = "leads.properia.pt";
        for (var recipient : toFull) {
            var email = (String) recipient.getOrDefault("Email", "");
            if (email.toLowerCase().endsWith("@" + inboundDomain)) {
                var localPart = email.split("@")[0].toLowerCase();
                var matcher = java.util.regex.Pattern.compile("^leads-([a-f0-9]{48})$").matcher(localPart);
                if (matcher.matches()) return matcher.group(1);
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
