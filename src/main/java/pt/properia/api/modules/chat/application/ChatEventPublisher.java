package pt.properia.api.modules.chat.application;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.chat.application.dto.MessageDto;

import java.util.Map;
import java.util.UUID;

@Service
public class ChatEventPublisher {

    private final SimpMessagingTemplate messaging;

    public ChatEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void publishNewMessage(UUID conversationId, UUID advertiserId, UUID buyerUserId, MessageDto message) {
        var payload = Map.of(
            "type", "new_message",
            "conversationId", conversationId.toString(),
            "message", Map.of(
                "id", message.id().toString(),
                "conversationId", message.conversationId().toString(),
                "senderType", message.senderType(),
                "senderUserId", message.senderUserId() != null ? message.senderUserId().toString() : null,
                "body", message.body() != null ? message.body() : "",
                "createdAt", message.createdAt() != null ? message.createdAt().toString() : null
            )
        );

        // Push to conversation topic (both buyer and advertiser can subscribe)
        messaging.convertAndSend("/topic/conv." + conversationId, payload);

        // Push unread snapshot to advertiser inbox
        messaging.convertAndSend("/topic/adv." + advertiserId, Map.of(
            "type", "unread_update",
            "conversationId", conversationId.toString()
        ));

        // Push unread snapshot to buyer
        if (buyerUserId != null) {
            messaging.convertAndSend("/topic/buyer." + buyerUserId, Map.of(
                "type", "unread_update",
                "conversationId", conversationId.toString()
            ));
        }
    }
}
