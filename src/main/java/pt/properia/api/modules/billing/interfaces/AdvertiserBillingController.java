package pt.properia.api.modules.billing.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.billing.application.BillingService;
import pt.properia.api.modules.billing.interfaces.request.CheckoutRequest;
import pt.properia.api.modules.billing.interfaces.request.PortalRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class AdvertiserBillingController {

    private final BillingService billingService;
    private final JdbcClient jdbc;

    public AdvertiserBillingController(BillingService billingService, JdbcClient jdbc) {
        this.billingService = billingService;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/billing/checkout")
    public ResponseEntity<?> createCheckout(
            @RequestBody CheckoutRequest body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var result = billingService.createCheckout(advertiserId, body.targetPlanCode(), body.billingCycle(), body.returnUrl());
        return ResponseEntity.ok(Map.of("data", Map.of("url", result.url())));
    }

    @PostMapping("/api/billing/portal")
    public ResponseEntity<?> createPortal(
            @RequestBody PortalRequest body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var result = billingService.createPortalSession(advertiserId, body.returnUrl());
        return ResponseEntity.ok(Map.of("data", Map.of("url", result.url())));
    }

    // Credit pack sizes (credits, price in €)
    private static final Map<String, int[]> CREDIT_PACKS = Map.of(
        "basic",        new int[]{5,  15},
        "standard",     new int[]{15, 39},
        "professional", new int[]{40, 89}
    );

    @PostMapping("/api/advertiser/billing/credits")
    public ResponseEntity<?> purchaseCredits(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var packCode = body.getOrDefault("packCode", "basic").toString();
        var returnUrl = body.getOrDefault("returnUrl", "/anunciante/plano").toString();

        if (!CREDIT_PACKS.containsKey(packCode)) {
            throw new DomainException("BAD_REQUEST", "Pack de créditos inválido.", 400);
        }

        var result = billingService.createCreditCheckout(advertiserId, packCode, returnUrl);
        return ResponseEntity.ok(Map.of("data", Map.of("url", result.url())));
    }

    @GetMapping("/api/advertiser/billing/credits")
    public ResponseEntity<?> getCredits(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var balance = billingService.getCreditBalance(advertiserId);
        var transactions = jdbc.sql("""
                SELECT id::text, type, amount, balance_after, description, created_at
                FROM properia.advertiser_credit_transactions
                WHERE advertiser_id = :adv
                ORDER BY created_at DESC
                LIMIT 50
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", rs.getString("id"));
                row.put("type", rs.getString("type"));
                row.put("amount", rs.getInt("amount"));
                row.put("balanceAfter", rs.getInt("balance_after"));
                row.put("description", rs.getString("description"));
                var ts = rs.getTimestamp("created_at");
                row.put("createdAt", ts != null ? ts.toInstant().toString() : null);
                return row;
            })
            .list();
        var data = new LinkedHashMap<String, Object>();
        data.put("balance", balance);
        data.put("transactions", transactions);
        return ResponseEntity.ok(Map.of("data", data));
    }

    @GetMapping("/api/advertiser/plan")
    public ResponseEntity<?> getPlan(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var info = billingService.getPlanInfo(advertiserId);

        var storedPlanCode = info.planCode() != null ? info.planCode() : "starter";

        // ── Trial state ───────────────────────────────────────────────────────
        var trial = new LinkedHashMap<String, Object>();
        trial.put("isActive", false);
        trial.put("source", "none");
        trial.put("startsAt", null);
        trial.put("endsAt", null);
        trial.put("trialPlanCode", null);
        trial.put("daysRemaining", null);
        trial.put("isExpiringSoon", false);

        boolean trialExpired = false;
        String trialExpiredPlanCode = null;
        String effectivePlanCode = storedPlanCode;

        if (info.trialActivatedAt() != null) {
            try {
                var activatedAt = Instant.parse(info.trialActivatedAt());
                // Usa o trialEndsAt persistido por activateTrial() (180 dias — o combinado atual).
                // Fallback só para trials legados ativados antes de este campo existir — esses
                // foram concedidos ao abrigo da regra antiga (40 dias), por isso mantém-se 40
                // aqui para não estender retroativamente trials já em curso ou já expirados.
                var endsAt = info.trialEndsAt() != null
                    ? Instant.parse(info.trialEndsAt())
                    : activatedAt.plus(40, ChronoUnit.DAYS);
                var now = Instant.now();
                if (now.isBefore(endsAt)) {
                    long days = ChronoUnit.DAYS.between(now, endsAt);
                    trial.put("isActive", true);
                    trial.put("source", "auto_agency");
                    trial.put("startsAt", activatedAt.toString());
                    trial.put("endsAt", endsAt.toString());
                    trial.put("trialPlanCode", "business");
                    trial.put("daysRemaining", (int) days);
                    trial.put("isExpiringSoon", days <= 3);
                    effectivePlanCode = "business";
                } else {
                    trialExpired = true;
                    trialExpiredPlanCode = "business";
                    effectivePlanCode = "starter";
                }
            } catch (Exception ignored) {}
        }

        // ── Usage stats ───────────────────────────────────────────────────────
        var activeListings = jdbc.sql("""
                SELECT COUNT(*) FROM properia.listings
                WHERE advertiser_id = :adv AND status = 'published'
                """).param("adv", advertiserId).query(Long.class).single();
        var teamMembers = jdbc.sql("""
                SELECT COUNT(*) FROM properia.advertiser_users WHERE advertiser_id = :adv
                """).param("adv", advertiserId).query(Long.class).single();

        var advertiserName = jdbc.sql("""
                SELECT brand_name FROM properia.advertisers WHERE id = :adv
                """).param("adv", advertiserId)
            .query((rs, n) -> rs.getString("brand_name")).optional().orElse("Anunciante");

        var caps = capabilities(effectivePlanCode);
        int maxListings = (int) caps.get("maxListings");
        int maxTeam = (int) caps.get("maxTeamMembers");
        boolean listingsLimitReached = maxListings != -1 && activeListings >= maxListings;
        boolean teamLimitExceeded = maxTeam != -1 && teamMembers > maxTeam;

        var pilot = new LinkedHashMap<String, Object>();
        pilot.put("isActive", false);
        pilot.put("endsAt", null);
        pilot.put("daysRemaining", null);
        pilot.put("loyaltyDiscountPct", 0);

        var usage = Map.of(
            "activeListings", activeListings.intValue(),
            "featuredListings", 0,
            "teamMembers", teamMembers.intValue(),
            "onlineVisitsThisMonth", 0
        );
        var limitsReached = Map.of(
            "listings", listingsLimitReached,
            "featuredListings", false,
            "teamMembers", teamLimitExceeded,
            "onlineVisits", false
        );
        var upgrade = Map.of(
            "recommendedPlanCode", "business".equals(effectivePlanCode) ? "business" : "pro",
            "ctaLabel", "Fazer upgrade"
        );

        var data = new LinkedHashMap<String, Object>();
        data.put("advertiserId", advertiserId.toString());
        data.put("advertiserName", advertiserName != null ? advertiserName : "Anunciante");
        data.put("planCode", storedPlanCode);
        data.put("basePlanCode", storedPlanCode);
        data.put("effectivePlanCode", effectivePlanCode);
        data.put("planLabel", planLabel(effectivePlanCode));
        data.put("capabilities", caps);
        data.put("trial", trial);
        data.put("pilot", pilot);
        data.put("paymentStatus", info.paymentStatus() != null ? info.paymentStatus() : "none");
        data.put("starterCreditsGranted", "starter".equals(effectivePlanCode));
        data.put("trialConsumed", trialExpired);
        data.put("trialConsumedPlanCode", trialExpired ? trialExpiredPlanCode : null);
        data.put("trialExpired", trialExpired);
        data.put("trialExpiredPlanCode", trialExpiredPlanCode);
        data.put("usage", usage);
        data.put("limitsReached", limitsReached);
        data.put("upgrade", upgrade);
        data.put("sponsoredPlacementDisclosure", "");

        return ResponseEntity.ok(Map.of("data", data));
    }

    private Map<String, Object> capabilities(String planCode) {
        boolean isPro = "pro".equals(planCode) || "business".equals(planCode) || "pilot".equals(planCode);
        boolean isBusiness = "business".equals(planCode);
        var caps = new LinkedHashMap<String, Object>();
        caps.put("maxListings", isBusiness ? -1 : isPro ? 15 : 5);
        caps.put("maxFeaturedListings", isBusiness ? 10 : isPro ? 3 : 0);
        caps.put("featuredPlacement", isPro);
        caps.put("aiEnrichment", isPro);
        caps.put("crm", isPro);
        caps.put("pipeline", isPro);
        caps.put("chat", isPro);
        caps.put("leadExport", isBusiness ? "full" : isPro ? "csv" : "none");
        caps.put("maxTeamMembers", isBusiness ? -1 : isPro ? 5 : 1);
        caps.put("analytics", isPro);
        caps.put("analyticsExport", isBusiness);
        caps.put("maxOnlineVisitsPerMonth", isBusiness ? -1 : isPro ? 20 : 5);
        caps.put("apiAccess", isBusiness);
        caps.put("crmIntegration", isBusiness);
        caps.put("partialBranding", isPro);
        caps.put("supportLevel", isBusiness ? "priority" : isPro ? "email" : "self_serve");
        caps.put("leadsUnlocked", isPro);
        caps.put("maxBuyerProfiles", isBusiness ? -1 : isPro ? 50 : 0);
        caps.put("buyerMatchNotifications", isPro);
        caps.put("buyerProfileExport", isBusiness);
        caps.put("maxVirtualToursPerMonth", isBusiness ? 15 : isPro ? 5 : 0);
        return caps;
    }

    private String planLabel(String planCode) {
        return switch (planCode) {
            case "pro" -> "Pro";
            case "business" -> "Business";
            case "pilot" -> "Pilot";
            default -> "Starter";
        };
    }

    @PostMapping("/api/advertiser/plan/trial")
    public ResponseEntity<?> activateTrial(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        billingService.activateTrial(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("activated", true)));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
