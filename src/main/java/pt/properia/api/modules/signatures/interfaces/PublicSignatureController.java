package pt.properia.api.modules.signatures.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.signatures.application.DocumentSignatureService;

import java.util.Map;

/**
 * Endpoints públicos (sem autenticação) da cerimónia de assinatura: o cliente abre o
 * link recebido por email, revê o PDF, introduz o código OTP e assina.
 */
@RestController
@RequestMapping("/api/public/signatures")
public class PublicSignatureController {

    private final DocumentSignatureService service;

    public PublicSignatureController(DocumentSignatureService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> view(@PathVariable String token) {
        return ResponseEntity.ok(Map.of("data", service.getPublicView(token)));
    }

    @GetMapping("/{token}/document.pdf")
    public ResponseEntity<byte[]> document(@PathVariable String token) {
        byte[] pdf = service.getPublicPdf(token);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"documento.pdf\"")
            .body(pdf);
    }

    @PostMapping("/{token}/sign")
    public ResponseEntity<?> sign(
            @PathVariable String token,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var otp = body.get("otp") == null ? null : body.get("otp").toString();
        var signature = body.get("signatureImage") == null ? null : body.get("signatureImage").toString();
        var result = service.sign(token, otp, signature, clientIp(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping("/verify/{hash}")
    public ResponseEntity<?> verify(@PathVariable String hash) {
        return ResponseEntity.ok(Map.of("data", service.verifyByHash(hash)));
    }

    /** Atrás do proxy do Render, o IP real vem no X-Forwarded-For. */
    private String clientIp(HttpServletRequest request) {
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
