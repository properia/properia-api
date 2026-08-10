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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/advertiser/chat")
public class AdvertiserChatController {

    private static final Logger log = LoggerFactory.getLogger(AdvertiserChatController.class);

    private final ChatService chatService;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard;

    public AdvertiserChatController(ChatService chatService, JdbcClient jdbc, ObjectMapper objectMapper,
                                   pt.properia.api.shared.infrastructure.web.PlanAccessGuard planGuard) {
        this.chatService = chatService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.planGuard = planGuard;
    }

    // Subquery reutilizada: nº de mensagens do comprador (nunca notas internas)
    // criadas depois do último "last_read_at" registado para o utilizador atual
    // nesta conversa — cai para created_at da conversa se nunca leu.
    private static final String UNREAD_COUNT_SQL = """
            (
                SELECT COUNT(*) FROM properia.chat_messages m
                WHERE m.conversation_id = c.id
                  AND m.sender_type::text = 'buyer'
                  AND m.is_internal = false
                  AND m.created_at > COALESCE(
                      (SELECT r.last_read_at FROM properia.chat_conversation_reads r
                       WHERE r.conversation_id = c.id AND r.user_id = :me),
                      c.created_at - INTERVAL '1 second'
                  )
            )::int
            """;

    // Mesma condição de "por responder": última mensagem do comprador ainda sem
    // resposta da equipa (independente de quem já leu) — usada no filtro unanswered.
    private static final String HAS_UNANSWERED_BUYER_MESSAGE_SQL = """
            EXISTS (
                SELECT 1 FROM properia.chat_messages m
                WHERE m.conversation_id = c.id
                  AND m.sender_type::text = 'buyer'
                  AND m.created_at > COALESCE(
                      (SELECT MAX(m2.created_at) FROM properia.chat_messages m2
                       WHERE m2.conversation_id = c.id
                         AND m2.sender_type::text = 'advertiser_member'
                         AND m2.is_internal = false),
                      c.created_at - INTERVAL '1 second'
                  )
            )
            """;

    @GetMapping("/conversations")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean unanswered) {
        var advertiserId = requireAdvertiserId(claims);
        var role = membershipRoleOf(advertiserId, claims.userId());
        boolean forceMine = "sales".equals(role);
        boolean wantsMine = forceMine || "mine".equalsIgnoreCase(scope);

        var whereParts = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        whereParts.add("c.advertiser_id = :adv");
        params.put("adv", advertiserId);
        params.put("me", claims.userId());

        if (wantsMine) {
            whereParts.add("COALESCE(ld.assigned_to, li.owner_user_id) = :me");
        }
        if (status != null && !status.isBlank() && !"todas".equalsIgnoreCase(status)) {
            whereParts.add("c.status::text = :status");
            params.put("status", status);
        }
        if (unanswered) {
            whereParts.add(HAS_UNANSWERED_BUYER_MESSAGE_SQL);
        }

        // Espaço final é deliberado: sem ele, concatenar esta String simples com o
        // próximo text block (que começa logo em "ORDER BY...", sem newline — o
        // Java despe a quebra de linha inicial de um text block) cola o último
        // token com a palavra seguinte (ex.: ":adv" + "ORDER" = parâmetro
        // ":advORDER" inexistente). Já aconteceu em produção — não remover.
        var whereClause = "WHERE " + String.join(" AND ", whereParts) + " ";
        var sql = jdbc.sql("""
                SELECT
                    c.id::text, c.advertiser_id::text, c.listing_id::text,
                    c.lead_id::text, c.buyer_user_id::text, c.status::text,
                    c.last_message_at, c.last_message_preview, c.closed_at,
                    c.created_at, c.updated_at,
                    u.full_name AS buyer_name, u.avatar_url AS buyer_avatar,
                    li.id::text AS l_id, li.public_id AS l_public_id, li.title AS l_title,
                    li.business_type::text AS l_business_type,
                    li.city AS l_city, li.district AS l_district,
                    assignee.id::text AS assigned_to_user_id, assignee.full_name AS assigned_to_name,
                    (COALESCE(ld.assigned_to, li.owner_user_id) = :me) AS is_mine,
                    """ + UNREAD_COUNT_SQL + """
                     AS unread_count
                FROM properia.chat_conversations c
                LEFT JOIN properia.app_users u ON u.id = c.buyer_user_id
                LEFT JOIN properia.leads ld ON ld.id = c.lead_id
                LEFT JOIN properia.listings li ON li.id = c.listing_id
                LEFT JOIN properia.app_users assignee ON assignee.id = COALESCE(ld.assigned_to, li.owner_user_id)
                """ + whereClause + """
                ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
                """);
        for (var e : params.entrySet()) sql = sql.param(e.getKey(), e.getValue());
        var items = sql.query((rs, n) -> buildConversationRow(rs)).list();

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> get(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        assertConversationAccessible(id, advertiserId, claims.userId());

        var conv = jdbc.sql("""
                SELECT
                    c.id::text, c.advertiser_id::text, c.listing_id::text,
                    c.lead_id::text, c.buyer_user_id::text, c.status::text,
                    c.last_message_at, c.last_message_preview, c.closed_at,
                    c.created_at, c.updated_at,
                    u.full_name AS buyer_name, u.avatar_url AS buyer_avatar,
                    li.id::text AS l_id, li.public_id AS l_public_id, li.title AS l_title,
                    li.business_type::text AS l_business_type,
                    li.city AS l_city, li.district AS l_district,
                    assignee.id::text AS assigned_to_user_id, assignee.full_name AS assigned_to_name,
                    (COALESCE(ld.assigned_to, li.owner_user_id) = :me) AS is_mine,
                    0 AS unread_count
                FROM properia.chat_conversations c
                LEFT JOIN properia.app_users u ON u.id = c.buyer_user_id
                LEFT JOIN properia.leads ld ON ld.id = c.lead_id
                LEFT JOIN properia.listings li ON li.id = c.listing_id
                LEFT JOIN properia.app_users assignee ON assignee.id = COALESCE(ld.assigned_to, li.owner_user_id)
                WHERE c.id = :id AND c.advertiser_id = :adv
                """)
            .param("id", id)
            .param("adv", advertiserId)
            .param("me", claims.userId())
            .query((rs, n) -> buildConversationRow(rs))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Conversa não encontrada.", 404));

        var messages = jdbc.sql("""
                SELECT id::text, conversation_id::text, sender_type::text,
                       sender_user_id::text, message_type::text, body, is_internal, created_at
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
                msg.put("isInternal", rs.getBoolean("is_internal"));
                msg.put("createdAt", toIso(rs.getTimestamp("created_at")));
                msg.put("isRead", true);
                return msg;
            })
            .list();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "conversation", conv,
            "messages", messages
        )));
    }

    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {
        var advertiserId = requireAdvertiserId(claims);
        var conversationId = UUID.fromString((String) body.get("conversationId"));
        var message = (String) body.get("body");
        if (message == null || message.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "A mensagem não pode estar vazia.");
        }
        boolean isInternal = Boolean.TRUE.equals(body.get("isInternal"));
        var msg = chatService.sendAdvertiserMessage(conversationId, advertiserId, claims.userId(), message, isInternal);
        return ResponseEntity.ok(Map.of("data", Map.of("message", msg)));
    }

    @PostMapping("/conversations/{id}/close")
    public ResponseEntity<?> close(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        chatService.closeConversation(id, advertiserId, claims.userId());

        var closedAt = jdbc.sql("""
                SELECT closed_at FROM properia.chat_conversations WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) -> toIso(rs.getTimestamp("closed_at")))
            .optional()
            .orElse(Instant.now().toString());

        var result = new LinkedHashMap<String, Object>();
        result.put("conversationId", id.toString());
        result.put("status", "closed");
        result.put("closedAt", closedAt);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<?> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        assertConversationAccessible(id, advertiserId, claims.userId());

        var readAt = Instant.now();
        jdbc.sql("""
                INSERT INTO properia.chat_conversation_reads (conversation_id, user_id, last_read_at)
                VALUES (:cid, :uid, :readAt)
                ON CONFLICT (conversation_id, user_id) DO UPDATE SET last_read_at = EXCLUDED.last_read_at
                """)
            .param("cid", id)
            .param("uid", claims.userId())
            .param("readAt", Timestamp.from(readAt))
            .update();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "conversationId", id.toString(),
            "readAt", readAt.toString()
        )));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var role = membershipRoleOf(advertiserId, claims.userId());
        boolean scopedToSelf = "sales".equals(role);
        var emitter = new SseEmitter(0L);

        try {
            // Espaço final deliberado — ver comentário em whereClause (list()) sobre
            // concatenação de String simples colada ao início de um text block.
            var scopeClause = scopedToSelf
                ? "AND COALESCE(ld.assigned_to, li.owner_user_id) = :me "
                : "";

            var unreadCount = jdbc.sql("""
                    SELECT COALESCE(SUM(sub.cnt), 0)::bigint FROM (
                        SELECT
                            """ + UNREAD_COUNT_SQL + """
                             AS cnt
                        FROM properia.chat_conversations c
                        LEFT JOIN properia.leads ld ON ld.id = c.lead_id
                        LEFT JOIN properia.listings li ON li.id = c.listing_id
                        WHERE c.advertiser_id = :adv
                        """ + scopeClause + """
                    ) sub
                    """)
                .param("adv", advertiserId)
                .param("me", claims.userId())
                .query(Long.class).single();

            var latestConv = jdbc.sql("""
                    SELECT c.id::text AS id, c.last_message_at
                    FROM properia.chat_conversations c
                    LEFT JOIN properia.leads ld ON ld.id = c.lead_id
                    LEFT JOIN properia.listings li ON li.id = c.listing_id
                    WHERE c.advertiser_id = :adv
                    """ + scopeClause + """
                    ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
                    LIMIT 1
                    """)
                .param("adv", advertiserId)
                .param("me", claims.userId())
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
            log.debug("Chat SSE snapshot error: {}", e.getMessage());
        } finally {
            emitter.complete();
        }

        return emitter;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private LinkedHashMap<String, Object> buildConversationRow(ResultSet rs) throws SQLException {
        var counterpart = new LinkedHashMap<String, Object>();
        counterpart.put("name", Objects.requireNonNullElse(rs.getString("buyer_name"), "Comprador"));
        counterpart.put("roleLabel", "Comprador");
        counterpart.put("avatarUrl", rs.getString("buyer_avatar"));

        var listing = new LinkedHashMap<String, Object>();
        listing.put("id", rs.getString("l_id"));
        listing.put("publicId", rs.getString("l_public_id"));
        listing.put("title", Objects.requireNonNullElse(rs.getString("l_title"), "Imóvel"));
        listing.put("businessType", Objects.requireNonNullElse(rs.getString("l_business_type"), "sale"));
        listing.put("city", rs.getString("l_city"));
        listing.put("district", rs.getString("l_district"));

        var row = new LinkedHashMap<String, Object>();
        row.put("id", rs.getString("id"));
        row.put("advertiserId", rs.getString("advertiser_id"));
        row.put("listingId", rs.getString("listing_id"));
        row.put("leadId", rs.getString("lead_id"));
        row.put("status", rs.getString("status"));
        row.put("createdAt", toIso(rs.getTimestamp("created_at")));
        row.put("updatedAt", toIso(rs.getTimestamp("updated_at")));
        row.put("closedAt", toIso(rs.getTimestamp("closed_at")));
        row.put("lastMessageAt", toIso(rs.getTimestamp("last_message_at")));
        row.put("lastMessagePreview", rs.getString("last_message_preview"));
        row.put("unreadCount", rs.getInt("unread_count"));
        row.put("assignedToUserId", rs.getString("assigned_to_user_id"));
        row.put("assignedToName", rs.getString("assigned_to_name"));
        row.put("isMine", rs.getBoolean("is_mine"));
        row.put("counterpart", counterpart);
        row.put("listing", listing);
        return row;
    }

    private static String toIso(Timestamp ts) {
        return ts != null ? ts.toInstant().toString() : null;
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        // Chat com interessados é Pro+ — impor no servidor, não só na UI.
        planGuard.requireProFeatures(claims.activeAdvertiserId());
        return claims.activeAdvertiserId();
    }

    private String membershipRoleOf(UUID advertiserId, UUID userId) {
        return jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query(String.class).optional().orElse(null);
    }

    /** Sales só acede a conversas do lead/imóvel de que é responsável — 404 para não confirmar existência. */
    private void assertConversationAccessible(UUID conversationId, UUID advertiserId, UUID userId) {
        if (!"sales".equals(membershipRoleOf(advertiserId, userId))) return;

        var owned = jdbc.sql("""
                SELECT 1 FROM properia.chat_conversations c
                LEFT JOIN properia.leads ld ON ld.id = c.lead_id
                LEFT JOIN properia.listings li ON li.id = c.listing_id
                WHERE c.id = :id AND c.advertiser_id = :adv
                  AND (ld.assigned_to = :uid OR li.owner_user_id = :uid)
                """).param("id", conversationId).param("adv", advertiserId).param("uid", userId)
            .query(Integer.class).optional().isPresent();
        if (!owned) throw new DomainException("NOT_FOUND", "Conversa não encontrada.", 404);
    }
}
