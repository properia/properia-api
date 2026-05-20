package pt.properia.api.modules.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "sender_type", nullable = false)
    @ColumnTransformer(write = "?::properia.chat_message_sender_type")
    private String senderType;

    @Column(name = "sender_user_id")
    private UUID senderUserId;

    @Column(name = "message_type", nullable = false)
    @ColumnTransformer(write = "?::properia.chat_message_type")
    private String messageType = "text";

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ChatMessage() {}

    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getListingId() { return listingId; }
    public UUID getLeadId() { return leadId; }
    public String getSenderType() { return senderType; }
    public UUID getSenderUserId() { return senderUserId; }
    public String getMessageType() { return messageType; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }

    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public void setSenderType(String senderType) { this.senderType = senderType; }
    public void setSenderUserId(UUID senderUserId) { this.senderUserId = senderUserId; }
    public void setBody(String body) { this.body = body; }
}
