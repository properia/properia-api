package pt.properia.api.modules.chat.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.chat.application.ChatService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class BuyerChatController {

    private final ChatService chatService;

    public BuyerChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", chatService.listForBuyer(claims.userId())
        )));
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> getOrCreate(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, String> body) {
        requireAuth(claims);
        var listingId = UUID.fromString(body.get("listingId"));
        var initialMessage = body.get("message");
        var conv = chatService.getOrCreateConversation(listingId, claims.userId(), initialMessage);
        return ResponseEntity.ok(Map.of("data", conv));
    }

    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, String> body) {
        requireAuth(claims);
        var conversationId = UUID.fromString(body.get("conversationId"));
        var message = body.get("body");
        if (message == null || message.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "A mensagem não pode estar vazia.");
        }
        var msg = chatService.sendBuyerMessage(conversationId, claims.userId(), message);
        return ResponseEntity.ok(Map.of("data", msg));
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Autenticação necessária.", 401);
    }
}
