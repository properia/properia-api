package pt.properia.api.modules.chat.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pt.properia.api.modules.chat.application.ChatService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class BuyerChatController {

    private static final Logger log = LoggerFactory.getLogger(BuyerChatController.class);

    private final ChatService chatService;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public BuyerChatController(ChatService chatService, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", chatService.listForBuyer(claims.userId())
        )));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);

        var conv = jdbc.sql("""
                SELECT c.id::text, c.advertiser_id::text, c.listing_id::text,
                       c.lead_id::text, c.buyer_user_id::text, c.status::text,
                       c.last_message_at, c.last_message_preview, c.closed_at,
                       c.created_at, c.updated_at,
                       l.id::text AS l_id, l.public_id AS l_public_id, l.title AS l_title,
                       l.business_type::text AS l_business_type,
                       l.city AS l_city, l.district AS l_district
                FROM properia.chat_conversations c
                LEFT JOIN properia.listings l ON l.id = c.listing_id
                WHERE c.id = :id AND c.buyer_user_id = :uid
                """)
            .param("id", id)
            .param("uid", claims.userId())
            .query((rs, n) -> {
                var listing = new LinkedHashMap<String, Object>();
                listing.put("id", rs.getString("l_id"));
                listing.put("publicId", rs.getString("l_public_id"));
                listing.put("title", rs.getString("l_title") != null ? rs.getString("l_title") : "Imóvel");
                listing.put("businessType", rs.getString("l_business_type") != null ? rs.getString("l_business_type") : "sale");
                listing.put("city", rs.getString("l_city"));
                listing.put("district", rs.getString("l_district"));

                var row = new LinkedHashMap<String, Object>();
                row.put("id", rs.getString("id"));
                row.put("advertiserId", rs.getString("advertiser_id"));
                row.put("listingId", rs.getString("listing_id"));
                row.put("leadId", rs.getString("lead_id"));
                row.put("buyerUserId", rs.getString("buyer_user_id"));
                row.put("status", rs.getString("status"));
                row.put("createdAt", toIso(rs.getTimestamp("created_at")));
                row.put("updatedAt", toIso(rs.getTimestamp("updated_at")));
                row.put("closedAt", toIso(rs.getTimestamp("closed_at")));
                row.put("lastMessageAt", toIso(rs.getTimestamp("last_message_at")));
                row.put("lastMessagePreview", rs.getString("last_message_preview"));
                row.put("listing", listing);
                return row;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Conversa não encontrada.", 404));

        var messages = jdbc.sql("""
                SELECT id::text, conversation_id::text, sender_type::text,
                       sender_user_id::text, message_type::text, body, created_at
                FROM properia.chat_messages
                WHERE conversation_id = :id
                ORDER BY created_at ASC
                """)
            .param("id", id)
            .query((rs, n) -> {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("id", rs.getString("id"));
                msg.put("conversationId", rs.getString("conversation_id"));
                msg.put("senderType", rs.getString("sender_type"));
                msg.put("senderUserId", rs.getString("sender_user_id"));
                msg.put("messageType", rs.getString("message_type"));
                msg.put("body", rs.getString("body"));
                msg.put("createdAt", toIso(rs.getTimestamp("created_at")));
                return msg;
            })
            .list();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "conversation", conv,
            "messages", messages
        )));
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> getOrCreate(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, String> body) {
        requireAuth(claims);
        var listingId = UUID.fromString(body.get("listingId"));
        var initialMessage = body.get("initialMessage");
        var conv = chatService.getOrCreateConversation(listingId, claims.userId(), initialMessage);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("conversationId", conv.id().toString());
        result.put("leadId", conv.leadId() != null ? conv.leadId().toString() : null);
        return ResponseEntity.ok(Map.of("data", result));
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
        return ResponseEntity.ok(Map.of("data", Map.of("message", msg)));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<?> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        return ResponseEntity.ok(Map.of("data", Map.of("ok", true)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var emitter = new SseEmitter(0L);

        try {
            var unreadCount = jdbc.sql("""
                    SELECT COALESCE(SUM(sub.cnt), 0)::bigint FROM (
                        SELECT (
                            SELECT COUNT(*) FROM properia.chat_messages m
                            WHERE m.conversation_id = c.id
                              AND m.sender_type::text = 'advertiser_member'
                              AND m.created_at > COALESCE(
                                  (SELECT MAX(m2.created_at) FROM properia.chat_messages m2
                                   WHERE m2.conversation_id = c.id
                                     AND m2.sender_type::text = 'buyer'),
                                  c.created_at - INTERVAL '1 second'
                              )
                        ) AS cnt
                        FROM properia.chat_conversations c
                        WHERE c.buyer_user_id = :uid
                    ) sub
                    """)
                .param("uid", claims.userId())
                .query(Long.class).single();

            var latestConv = jdbc.sql("""
                    SELECT id::text AS id, last_message_at
                    FROM properia.chat_conversations
                    WHERE buyer_user_id = :uid
                    ORDER BY COALESCE(last_message_at, created_at) DESC
                    LIMIT 1
                    """)
                .param("uid", claims.userId())
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getString("id"));
                    m.put("at", rs.getTimestamp("last_message_at"));
                    return m;
                })
                .optional();

            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("totalConversations", 0L);
            snapshot.put("unreadCount", unreadCount);
            snapshot.put("latestConversationId", latestConv.map(m -> (Object) m.get("id")).orElse(null));
            snapshot.put("latestMessageAt", latestConv
                .map(m -> toIso((Timestamp) m.get("at")))
                .orElse(null));
            snapshot.put("emittedAt", Instant.now().toString());

            var json = objectMapper.writeValueAsString(snapshot);
            emitter.send(SseEmitter.event().name("chat-snapshot").data(json, MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            log.debug("Buyer chat SSE snapshot error: {}", e.getMessage());
        } finally {
            emitter.complete();
        }

        return emitter;
    }

    private static String toIso(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Autenticação necessária.", 401);
    }
}
