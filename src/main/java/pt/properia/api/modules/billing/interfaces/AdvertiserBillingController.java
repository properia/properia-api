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
        var result = billingService.createCheckout(advertiserId, body.planCode(), body.billingCycle(), body.returnUrl());
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

    @GetMapping("/api/advertiser/billing/credits")
    public ResponseEntity<?> getCredits(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var balance = billingService.getCreditBalance(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("balance", balance)));
    }

    @GetMapping("/api/advertiser/plan")
    public ResponseEntity<?> getPlan(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var info = billingService.getPlanInfo(advertiserId);

        var planCode = info.planCode() != null ? info.planCode() : "starter";

        // Usage stats
        var activeListings = jdbc.sql("""
                SELECT COUNT(*) FROM properia.listings
                WHERE advertiser_id = :adv AND status = 'published'
                """).param("adv", advertiserId).query(Long.class).single();
        var teamMembers = jdbc.sql("""
                SELECT COUNT(*) FROM properia.advertiser_users WHERE advertiser_id = :adv
                """).param("adv", advertiserId).query(Long.class).single();

        // Advertiser name
        var advertiserName = jdbc.sql("""
                SELECT brand_name FROM properia.advertisers WHERE id = :adv
                """).param("adv", advertiserId)
            .query((rs, n) -> rs.getString("brand_name")).optional().orElse("Anunciante");

        var caps = capabilities(planCode);
        int maxListings = (int) caps.get("maxListings");
        boolean listingsLimitReached = maxListings != -1 && activeListings >= maxListings;

        var trial = new LinkedHashMap<String, Object>();
        trial.put("isActive", false);
        trial.put("source", "none");
        trial.put("startsAt", null);
        trial.put("endsAt", null);
        trial.put("trialPlanCode", null);
        trial.put("daysRemaining", null);
        trial.put("isExpiringSoon", false);

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
            "teamMembers", false,
            "onlineVisits", false
        );
        var upgrade = Map.of(
            "recommendedPlanCode", "pro".equals(planCode) ? "business" : "pro",
            "ctaLabel", "Fazer upgrade"
        );

        var data = new LinkedHashMap<String, Object>();
        data.put("advertiserId", advertiserId.toString());
        data.put("advertiserName", advertiserName != null ? advertiserName : "Anunciante");
        data.put("basePlanCode", planCode);
        data.put("effectivePlanCode", planCode);
        data.put("planLabel", planLabel(planCode));
        data.put("capabilities", caps);
        data.put("trial", trial);
        data.put("pilot", pilot);
        data.put("paymentStatus", info.paymentStatus() != null ? info.paymentStatus() : "none");
        data.put("starterCreditsGranted", "starter".equals(planCode));
        data.put("trialConsumed", false);
        data.put("trialConsumedPlanCode", null);
        data.put("trialExpired", false);
        data.put("trialExpiredPlanCode", null);
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
        caps.put("maxListings", isBusiness ? -1 : isPro ? 15 : 3);
        caps.put("maxFeaturedListings", isBusiness ? 5 : isPro ? 2 : 0);
        caps.put("featuredPlacement", isPro);
        caps.put("aiEnrichment", isPro);
        caps.put("crm", isPro);
        caps.put("pipeline", isPro);
        caps.put("chat", isPro);
        caps.put("leadExport", isBusiness ? "full" : isPro ? "csv" : "none");
        caps.put("maxTeamMembers", isBusiness ? -1 : isPro ? 5 : 1);
        caps.put("analytics", isPro);
        caps.put("analyticsExport", isBusiness);
        caps.put("maxOnlineVisitsPerMonth", isBusiness ? -1 : isPro ? 30 : 5);
        caps.put("apiAccess", isBusiness);
        caps.put("crmIntegration", isBusiness);
        caps.put("partialBranding", isPro);
        caps.put("supportLevel", isBusiness ? "priority" : isPro ? "email" : "self_serve");
        caps.put("leadsUnlocked", isPro);
        caps.put("maxBuyerProfiles", isBusiness ? -1 : isPro ? 50 : 0);
        caps.put("buyerMatchNotifications", isPro);
        caps.put("buyerProfileExport", isBusiness);
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
