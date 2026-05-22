package pt.properia.api.modules.billing.application;

import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.billing.infrastructure.AdvertiserBillingRepository;
import pt.properia.api.modules.billing.infrastructure.StripeProperties;
import pt.properia.api.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingService {

    private final StripeProperties stripeProps;
    private final AdvertiserBillingRepository billingRepo;

    public BillingService(StripeProperties stripeProps, AdvertiserBillingRepository billingRepo) {
        this.stripeProps = stripeProps;
        this.billingRepo = billingRepo;
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    public record CheckoutResult(String url) {}

    public CheckoutResult createCheckout(UUID advertiserId, String planCode, String billingCycle, String returnUrl) {
        if (stripeProps.isFake()) {
            return new CheckoutResult(returnUrl + "?checkout=fake&plan=" + planCode);
        }

        Stripe.apiKey = stripeProps.getSecretKey();
        try {
            var priceId = stripeProps.resolvePriceId(planCode, billingCycle);
            var customerId = getOrCreateStripeCustomer(advertiserId);

            var params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build())
                .setSuccessUrl(returnUrl + "?checkout=success")
                .setCancelUrl(returnUrl + "?checkout=cancelled")
                .putMetadata("advertiserId", advertiserId.toString())
                .putMetadata("targetPlanCode", planCode)
                .build();

            var session = Session.create(params);
            return new CheckoutResult(session.getUrl());

        } catch (Exception e) {
            throw new DomainException("BILLING_ERROR", "Não foi possível criar a sessão de checkout.", 502);
        }
    }

    // ── Portal ────────────────────────────────────────────────────────────────

    public record PortalResult(String url) {}

    public PortalResult createPortalSession(UUID advertiserId, String returnUrl) {
        if (stripeProps.isFake()) {
            return new PortalResult(returnUrl + "?portal=fake");
        }

        Stripe.apiKey = stripeProps.getSecretKey();
        try {
            var customerId = getOrCreateStripeCustomer(advertiserId);
            var params = com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build();
            var session = com.stripe.model.billingportal.Session.create(params);
            return new PortalResult(session.getUrl());
        } catch (Exception e) {
            throw new DomainException("BILLING_ERROR", "Não foi possível criar a sessão do portal.", 502);
        }
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    public void handleWebhook(byte[] payload, String signature) {
        if (stripeProps.getWebhookSecret().isBlank()) return;

        Stripe.apiKey = stripeProps.getSecretKey();
        Event event;
        try {
            event = Webhook.constructEvent(
                new String(payload), signature, stripeProps.getWebhookSecret()
            );
        } catch (Exception e) {
            throw new DomainException("INVALID_SIGNATURE", "Assinatura Stripe inválida.", 400);
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
            default -> { /* ignore */ }
        }
    }

    // ── Trial ─────────────────────────────────────────────────────────────────

    public void activateTrial(UUID advertiserId) {
        var meta = billingRepo.getSnapshot(advertiserId).billingMetadata();
        if (meta.containsKey("trialActivatedAt")) {
            throw new DomainException("CONFLICT", "Trial já foi ativado para este anunciante.");
        }
        var now = java.time.Instant.now();
        var endsAt = now.plus(90, java.time.temporal.ChronoUnit.DAYS);
        billingRepo.updatePlanCode(advertiserId, "business");
        billingRepo.patchBillingMetadata(advertiserId, Map.of(
            "trialActivatedAt", now.toString(),
            "trialEndsAt", endsAt.toString(),
            "trialPlanCode", "business",
            "paymentStatus", "active"
        ));
    }

    // ── Credits ───────────────────────────────────────────────────────────────

    public int getCreditBalance(UUID advertiserId) {
        return billingRepo.getCreditBalance(advertiserId);
    }

    // ── Plan info ─────────────────────────────────────────────────────────────

    public record PlanInfo(String planCode, String paymentStatus, int creditBalance,
                           String trialActivatedAt, String stripeSubscriptionId) {}

    public PlanInfo getPlanInfo(UUID advertiserId) {
        var snapshot = billingRepo.getSnapshot(advertiserId);
        var meta = snapshot.billingMetadata();
        return new PlanInfo(
            snapshot.planCode() != null ? snapshot.planCode() : "free",
            meta.getOrDefault("paymentStatus", "none").toString(),
            snapshot.creditBalance(),
            meta.containsKey("trialActivatedAt") ? meta.get("trialActivatedAt").toString() : null,
            meta.containsKey("stripeSubscriptionId") ? meta.get("stripeSubscriptionId").toString() : null
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String getOrCreateStripeCustomer(UUID advertiserId) throws Exception {
        var snapshot = billingRepo.getSnapshot(advertiserId);
        var existingId = (String) snapshot.billingMetadata().get("stripeCustomerId");
        if (existingId != null && !existingId.isBlank()) return existingId;

        var customer = Customer.create(CustomerCreateParams.builder()
            .putMetadata("advertiserId", advertiserId.toString())
            .build());

        billingRepo.patchBillingMetadata(advertiserId, Map.of("stripeCustomerId", customer.getId()));
        return customer.getId();
    }

    private void handleCheckoutCompleted(Event event) {
        try {
            var session = (Session) event.getDataObjectDeserializer()
                .getObject().orElseThrow();
            var advertiserId = UUID.fromString(session.getMetadata().get("advertiserId"));
            var planCode = session.getMetadata().get("targetPlanCode");

            if (planCode != null) {
                billingRepo.updatePlanCode(advertiserId, planCode);
            }
            billingRepo.patchBillingMetadata(advertiserId, Map.of(
                "stripeSubscriptionId", session.getSubscription() != null ? session.getSubscription() : "",
                "stripeCustomerId", session.getCustomer() != null ? session.getCustomer() : "",
                "paymentStatus", "active",
                "lastCheckoutAt", java.time.Instant.now().toString()
            ));
        } catch (Exception ignored) {}
    }

    private void handleSubscriptionUpdated(Event event) {
        try {
            var subscription = (com.stripe.model.Subscription) event.getDataObjectDeserializer()
                .getObject().orElseThrow();
            var advertiserId = UUID.fromString(subscription.getMetadata().get("advertiserId"));
            var paymentStatus = derivePaymentStatus(subscription.getStatus());

            var patch = new HashMap<String, Object>();
            patch.put("stripeSubscriptionId", subscription.getId());
            if (paymentStatus != null) patch.put("paymentStatus", paymentStatus);
            billingRepo.patchBillingMetadata(advertiserId, patch);

            if ("cancelled".equals(paymentStatus)) {
                billingRepo.updatePlanCode(advertiserId, "free");
            }
        } catch (Exception ignored) {}
    }

    private void handleSubscriptionDeleted(Event event) {
        try {
            var subscription = (com.stripe.model.Subscription) event.getDataObjectDeserializer()
                .getObject().orElseThrow();
            var advertiserId = UUID.fromString(subscription.getMetadata().get("advertiserId"));
            billingRepo.updatePlanCode(advertiserId, "free");
            billingRepo.patchBillingMetadata(advertiserId, Map.of("paymentStatus", "cancelled"));
        } catch (Exception ignored) {}
    }

    private String derivePaymentStatus(String status) {
        return switch (status) {
            case "active", "trialing" -> "active";
            case "past_due", "unpaid", "incomplete", "incomplete_expired" -> "past_due";
            case "canceled" -> "cancelled";
            default -> null;
        };
    }
}
