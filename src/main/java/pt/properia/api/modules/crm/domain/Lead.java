package pt.properia.api.modules.crm.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leads", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.lead_source")
    private String source;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.lead_stage")
    private String stage = "new";

    @Column(name = "intent_type", nullable = false)
    @ColumnTransformer(write = "?::properia.intent_type")
    private String intentType = "buy";

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Lead() {}

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getUserId() { return userId; }
    public UUID getAdvertiserId() { return advertiserId; }
    public String getSource() { return source; }
    public String getStage() { return stage; }
    public String getIntentType() { return intentType; }
    public String getMessage() { return message; }
    public String getContactName() { return contactName; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public BigDecimal getScore() { return score; }
    public UUID getAssignedTo() { return assignedTo; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setSource(String source) { this.source = source; }
    public void setStage(String stage) { this.stage = stage; }
    public void setIntentType(String intentType) { this.intentType = intentType; }
    public void setMessage(String message) { this.message = message; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public void setAssignedTo(UUID assignedTo) { this.assignedTo = assignedTo; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
