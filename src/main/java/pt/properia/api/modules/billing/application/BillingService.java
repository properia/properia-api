package pt.properia.api.modules.billing.application;

import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    // ── Credit packs ──────────────────────────────────────────────────────────
    // Fonte única de verdade no backend — tem de espelhar shared/billing-catalog.ts no FE.
    // O catálogo duplicado no controller (antes desta correção) foi a causa de o packCode
    // nunca ter sido validado consistentemente com o que o checkout realmente cobrava.

    public record CreditPack(String code, int credits, int priceEur) {}

    private static final Map<String, CreditPack> CREDIT_PACKS = Map.of(
        "basic",        new CreditPack("basic", 5, 15),
        "standard",     new CreditPack("standard", 15, 39),
        "professional", new CreditPack("professional", 40, 89)
    );

    public CreditPack resolveCreditPack(String packCode) {
        var pack = CREDIT_PACKS.get(packCode);
        if (pack == null) {
            throw new DomainException("BAD_REQUEST", "Pack de créditos inválido.", 400);
        }
        return pack;
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    public record CheckoutResult(String url) {}

    public CheckoutResult createCreditCheckout(UUID advertiserId, String packCode, String returnUrl) {
        var pack = resolveCreditPack(packCode);

        if (stripeProps.isFake()) {
            // Sem Stripe real para dar idempotência ao "retorno", guardamos a intenção agora
            // e o frontend resgata-a uma só vez em POST /credits/confirm-fake ao voltar.
            var sessionId = billingRepo.createFakeCheckoutSession(advertiserId, pack.code(), pack.credits());
            return new CheckoutResult(returnUrl + "?checkout=fake&session=" + sessionId + "&credits=" + pack.code());
        }

        Stripe.apiKey = stripeProps.getSecretKey();
        try {
            var customerId = getOrCreateStripeCustomer(advertiserId);

            var params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(customerId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency("eur")
                        .setUnitAmount((long) pack.priceEur() * 100)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(pack.credits() + " créditos Properia — pack " + pack.code())
                            .build())
                        .build())
                    .build())
                .setSuccessUrl(returnUrl + "?checkout=success&credits=" + pack.code())
                .setCancelUrl(returnUrl + "?checkout=cancelled")
                .putMetadata("advertiserId", advertiserId.toString())
                .putMetadata("creditPackCode", pack.code())
                .putMetadata("creditAmount", String.valueOf(pack.credits()))
                .build();

            var session = Session.create(params);
            return new CheckoutResult(session.getUrl());

        } catch (Exception e) {
            throw new DomainException("BILLING_ERROR", "Não foi possível criar a sessão de checkout.", 502);
        }
    }

    /**
     * Resgata uma sessão de checkout "fake" (modo dev) UMA só vez e credita o saldo.
     * Chamado pelo frontend ao detetar ?checkout=fake&session=... no retorno. Idempotente:
     * se a sessão já tiver sido resgatada (ex.: refresh da página), devolve o saldo atual
     * sem creditar de novo.
     */
    @Transactional
    public int confirmFakeCreditCheckout(UUID advertiserId, UUID sessionId) {
        var claimed = billingRepo.claimFakeCheckoutSession(sessionId, advertiserId);
        if (claimed.isEmpty()) {
            return billingRepo.getCreditBalance(advertiserId);
        }
        var newBalance = billingRepo.addCredits(advertiserId, claimed.get().credits());
        billingRepo.addCreditTransaction(advertiserId, "purchase", claimed.get().credits(), newBalance,
            sessionId.toString(), "Compra de créditos — pack " + claimed.get().packCode() + " (dev/fake)");
        return newBalance;
    }

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

    @Transactional
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

        // Idempotência: a Stripe entrega at-least-once e por vezes fora de ordem. Se o evento
        // já foi processado, ignora (evita duplicar créditos / baralhar o estado — correção #3).
        // A transação garante que a marca e as mutações commitam juntas.
        if (!billingRepo.markWebhookProcessed(event.getId(), event.getType())) {
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
            default -> { /* ignore */ }
        }
    }

    // ── Trial ─────────────────────────────────────────────────────────────────

    @Transactional
    public void activateTrial(UUID advertiserId) {
        var now = java.time.Instant.now();
        var endsAt = now.plus(180, java.time.temporal.ChronoUnit.DAYS);
        // Ativação atómica e idempotente: plano + metadata num único UPDATE guardado.
        // Se já estava ativo, o UPDATE não afeta linhas → CONFLICT (sem check-then-act).
        var activated = billingRepo.activateTrialOnce(advertiserId, "business", Map.of(
            "trialActivatedAt", now.toString(),
            "trialEndsAt", endsAt.toString(),
            "trialPlanCode", "business",
            "paymentStatus", "active"
        ));
        if (!activated) {
            throw new DomainException("CONFLICT", "Trial já foi ativado para este anunciante.");
        }
    }

    // ── Credits ───────────────────────────────────────────────────────────────

    public int getCreditBalance(UUID advertiserId) {
        return billingRepo.getCreditBalance(advertiserId);
    }

    @Transactional
    public void grantWelcomeCredits(UUID advertiserId, int amount) {
        // Incremento atómico + guarda idempotente: só concede (e regista no ledger) se ainda
        // não tinham sido concedidos. Elimina o lost-update e a dupla concessão (correção #2).
        var newBalance = billingRepo.grantWelcomeCreditsOnce(advertiserId, amount,
            java.time.Instant.now().toString());
        if (newBalance.isEmpty()) return; // já concedidos anteriormente
        billingRepo.addCreditTransaction(advertiserId, "bonus", amount, newBalance.get(), null,
            "Créditos de boas-vindas — bem-vindo à Properia!");
    }

    /**
     * Debita 1 crédito para desbloquear o contacto de um lead. Devolve o novo saldo se
     * debitou (registando no ledger), ou empty se o saldo era insuficiente — nesse caso
     * nada é alterado, e o chamador deve devolver 402 ao cliente.
     */
    @Transactional
    public java.util.Optional<Integer> spendLeadRevealCredit(UUID advertiserId, UUID leadId) {
        var newBalance = billingRepo.spendCreditOnce(advertiserId, 1);
        newBalance.ifPresent(balance -> billingRepo.addCreditTransaction(
            advertiserId, "lead_reveal", -1, balance, null,
            "Desbloqueio de contacto — lead " + leadId));
        return newBalance;
    }

    /** Planos Pro e superiores têm os contactos de leads sempre desbloqueados (sem custo). */
    public boolean hasLeadsUnlockedByPlan(UUID advertiserId) {
        var planCode = billingRepo.getSnapshot(advertiserId).planCode();
        return "pro".equals(planCode) || "business".equals(planCode) || "pilot".equals(planCode);
    }

    // ── Plan info ─────────────────────────────────────────────────────────────

    public record PlanInfo(String planCode, String paymentStatus, int creditBalance,
                           String trialActivatedAt, String trialEndsAt, String stripeSubscriptionId) {}

    public PlanInfo getPlanInfo(UUID advertiserId) {
        var snapshot = billingRepo.getSnapshot(advertiserId);
        var meta = snapshot.billingMetadata();
        return new PlanInfo(
            snapshot.planCode() != null ? snapshot.planCode() : "starter",
            meta.getOrDefault("paymentStatus", "none").toString(),
            snapshot.creditBalance(),
            meta.containsKey("trialActivatedAt") ? meta.get("trialActivatedAt").toString() : null,
            // Fonte de verdade da duração do trial: gravado por activateTrial() (180 dias).
            // Antes disto não era exposto e o controller recalculava com 14 dias hardcoded,
            // provocando downgrade prematuro de agências ainda dentro do trial combinado.
            meta.containsKey("trialEndsAt") ? meta.get("trialEndsAt").toString() : null,
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

        // Claim atómico: se dois checkouts correrem em paralelo, ambos podem criar um cliente
        // Stripe, mas só um id fica guardado — todos passam a usar esse (o outro fica órfão,
        // inerte). Evita fixar clientes diferentes em pedidos concorrentes (correção #7).
        return billingRepo.claimStripeCustomerId(advertiserId, customer.getId());
    }

    private void handleCheckoutCompleted(Event event) {
        try {
            var session = (Session) event.getDataObjectDeserializer()
                .getObject().orElseThrow();
            var advertiserId = UUID.fromString(session.getMetadata().get("advertiserId"));
            var creditPackCode = session.getMetadata().get("creditPackCode");

            // Compra de créditos (modo "payment", one-off) é um ramo distinto de subscrição
            // de plano (modo "subscription") — o event.id já garante que só entra aqui uma
            // vez (dedup em handleWebhook), por isso um incremento simples é seguro.
            if (creditPackCode != null) {
                var creditAmount = Integer.parseInt(session.getMetadata().get("creditAmount"));
                var newBalance = billingRepo.addCredits(advertiserId, creditAmount);
                billingRepo.addCreditTransaction(advertiserId, "purchase", creditAmount, newBalance,
                    session.getId(), "Compra de créditos — pack " + creditPackCode);
                return;
            }

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
