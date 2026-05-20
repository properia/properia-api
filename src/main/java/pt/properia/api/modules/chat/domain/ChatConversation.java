package pt.properia.api.modules.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_conversations", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.chat_conversation_status")
    private String status = "active";

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "last_message_preview")
    private String lastMessagePreview;

    @Column(name = "closed_at")
    private Instant closedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ChatConversation() {}

    public UUID getId() { return id; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getListingId() { return listingId; }
    public UUID getLeadId() { return leadId; }
    public UUID getBuyerUserId() { return buyerUserId; }
    public String getStatus() { return status; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public void setBuyerUserId(UUID buyerUserId) { this.buyerUserId = buyerUserId; }
    public void setStatus(String status) { this.status = status; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
