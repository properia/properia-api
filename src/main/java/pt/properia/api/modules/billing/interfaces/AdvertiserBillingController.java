package pt.properia.api.modules.billing.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.billing.application.BillingService;
import pt.properia.api.modules.billing.interfaces.request.CheckoutRequest;
import pt.properia.api.modules.billing.interfaces.request.PortalRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
public class AdvertiserBillingController {

    private final BillingService billingService;

    public AdvertiserBillingController(BillingService billingService) {
        this.billingService = billingService;
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
        return ResponseEntity.ok(Map.of("data", info));
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
