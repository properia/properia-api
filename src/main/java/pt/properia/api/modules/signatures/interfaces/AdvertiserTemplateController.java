package pt.properia.api.modules.signatures.interfaces;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.signatures.application.DocumentTemplateService;
import pt.properia.api.modules.signatures.application.DocumentTemplateService.CreateTemplateRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

/**
 * Gestão dos modelos de contrato próprios da agência (PDF prenchível).
 */
@RestController
@RequestMapping("/api/advertiser/signature-templates")
public class AdvertiserTemplateController {

    private final DocumentTemplateService service;

    public AdvertiserTemplateController(DocumentTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        return ResponseEntity.ok(Map.of("data", service.list(resolve(claims))));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateTemplateRequest req,
                                    @AuthenticationPrincipal JwtClaims claims) {
        return ResponseEntity.ok(Map.of("data", service.create(resolve(claims), req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id, @AuthenticationPrincipal JwtClaims claims) {
        return ResponseEntity.ok(Map.of("data", service.get(resolve(claims), id)));
    }

    @GetMapping("/{id}/document.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @AuthenticationPrincipal JwtClaims claims) {
        byte[] pdf = service.getPdf(resolve(claims), id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"modelo.pdf\"")
            .body(pdf);
    }

    @PostMapping("/{id}/suggest-fill")
    public ResponseEntity<?> suggestFill(@PathVariable UUID id,
                                         @RequestBody(required = false) Map<String, Object> body,
                                         @AuthenticationPrincipal JwtClaims claims) {
        UUID visitId = uuid(body, "visitId");
        UUID listingId = uuid(body, "listingId");
        var values = service.suggestFill(resolve(claims), id, visitId, listingId);
        return ResponseEntity.ok(Map.of("data", Map.of("values", values)));
    }

    private UUID uuid(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        try { return UUID.fromString(body.get(key).toString()); } catch (IllegalArgumentException e) { return null; }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, @AuthenticationPrincipal JwtClaims claims) {
        service.delete(resolve(claims), id);
        return ResponseEntity.ok(Map.of("data", Map.of("deleted", true)));
    }

    private UUID resolve(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
