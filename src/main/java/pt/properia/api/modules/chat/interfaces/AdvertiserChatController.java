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
@RequestMapping("/api/advertiser/chat")
public class AdvertiserChatController {

    private final ChatService chatService;

    public AdvertiserChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", chatService.listForAdvertiser(advertiserId)
        )));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        return ResponseEntity.ok(Map.of("data", chatService.getConversation(id, advertiserId)));
    }

    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, String> body) {
        var advertiserId = requireAdvertiserId(claims);
        var conversationId = UUID.fromString(body.get("conversationId"));
        var message = body.get("body");
        if (message == null || message.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "A mensagem não pode estar vazia.");
        }
        var msg = chatService.sendAdvertiserMessage(conversationId, advertiserId, claims.userId(), message);
        return ResponseEntity.ok(Map.of("data", msg));
    }

    @PostMapping("/conversations/{id}/close")
    public ResponseEntity<?> close(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        chatService.closeConversation(id, advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("closed", true)));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<?> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        // Read state tracked client-side for now
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
