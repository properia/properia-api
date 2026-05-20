package pt.properia.api.modules.crm.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "visits", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "buyer_user_id")
    private UUID buyerUserId;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.visit_mode")
    private String mode;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.visit_status")
    private String status = "requested";

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "meeting_url")
    private String meetingUrl;

    @Column(columnDefinition = "text")
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Visit() {}

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public UUID getListingId() { return listingId; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getBuyerUserId() { return buyerUserId; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getMeetingUrl() { return meetingUrl; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setBuyerUserId(UUID buyerUserId) { this.buyerUserId = buyerUserId; }
    public void setMode(String mode) { this.mode = mode; }
    public void setStatus(String status) { this.status = status; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }
    public void setNotes(String notes) { this.notes = notes; }
}
