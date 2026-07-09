package pt.properia.api.modules.signatures.interfaces;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.signatures.application.DocumentSignatureService;
import pt.properia.api.modules.signatures.application.DocumentSignatureService.CreateRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

/**
 * Gestão de documentos para assinatura (lado do anunciante/agente).
 */
@RestController
@RequestMapping("/api/advertiser/signatures")
public class AdvertiserSignatureController {

    private final DocumentSignatureService service;

    public AdvertiserSignatureController(DocumentSignatureService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        return ResponseEntity.ok(Map.of("data", service.list(advertiserId)));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody CreateRequest req,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        var dto = service.create(advertiserId, claims.userId(), req);
        return ResponseEntity.ok(Map.of("data", dto));
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<?> send(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        service.send(advertiserId, id);
        return ResponseEntity.ok(Map.of("data", Map.of("sent", true)));
    }

    @GetMapping("/{id}/document.pdf")
    public ResponseEntity<byte[]> document(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        byte[] pdf = service.getAdvertiserPdf(advertiserId, id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"documento.pdf\"")
            .body(pdf);
    }

    private UUID resolveAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
