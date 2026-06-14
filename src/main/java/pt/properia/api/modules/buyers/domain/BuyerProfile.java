package pt.properia.api.modules.buyers.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "buyer_profiles", schema = "properia")
public class BuyerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "assigned_to_user_id")
    private UUID assignedToUserId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "consent_status")
    @ColumnTransformer(write = "?::properia.buyer_consent_status")
    private String consentStatus = "pending";

    @Column(name = "consent_token")
    private UUID consentToken;

    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;

    @Column(name = "consent_expires_at")
    private Instant consentExpiresAt;

    @Column(name = "consent_ip_address")
    private String consentIpAddress;

    @Column(name = "consent_text_version")
    private String consentTextVersion = "1.0";

    @Column(name = "criteria", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> criteria = Map.of();

    @Column(name = "urgency")
    @ColumnTransformer(write = "?::properia.buyer_urgency")
    private String urgency = "exploring";

    @Column(name = "budget_bracket")
    @ColumnTransformer(write = "?::properia.buyer_budget_bracket")
    private String budgetBracket;

    @Column(name = "budget_approval")
    @ColumnTransformer(write = "?::properia.buyer_budget_approval")
    private String budgetApproval = "none";

    @Column(name = "situation")
    @ColumnTransformer(write = "?::properia.buyer_situation")
    private String situation = "buyer_only";

    @Column(name = "status")
    @ColumnTransformer(write = "?::properia.buyer_profile_status")
    private String status = "active";

    @Column(name = "close_reason")
    @ColumnTransformer(write = "?::properia.buyer_close_reason")
    private String closeReason;

    @Column(name = "last_contacted_at")
    private Instant lastContactedAt;

    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;

    @Column(name = "internal_notes")
    private String internalNotes;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Transient
    private Integer matchCount = 0;

    public BuyerProfile() {}

    public UUID getId() { return id; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getAssignedToUserId() { return assignedToUserId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getConsentStatus() { return consentStatus; }
    public UUID getConsentToken() { return consentToken; }
    public Instant getConsentAcceptedAt() { return consentAcceptedAt; }
    public Instant getConsentExpiresAt() { return consentExpiresAt; }
    public String getConsentIpAddress() { return consentIpAddress; }
    public String getConsentTextVersion() { return consentTextVersion; }
    public Map<String, Object> getCriteria() { return criteria; }
    public String getUrgency() { return urgency; }
    public String getBudgetBracket() { return budgetBracket; }
    public String getBudgetApproval() { return budgetApproval; }
    public String getSituation() { return situation; }
    public String getStatus() { return status; }
    public String getCloseReason() { return closeReason; }
    public Instant getLastContactedAt() { return lastContactedAt; }
    public Instant getNextFollowUpAt() { return nextFollowUpAt; }
    public String getInternalNotes() { return internalNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getMatchCount() { return matchCount; }
    public void setMatchCount(Integer matchCount) { this.matchCount = matchCount; }

    public void setId(UUID v) { this.id = v; }
    public void setAdvertiserId(UUID v) { this.advertiserId = v; }
    public void setAssignedToUserId(UUID v) { this.assignedToUserId = v; }
    public void setName(String v) { this.name = v; }
    public void setEmail(String v) { this.email = v; }
    public void setPhone(String v) { this.phone = v; }
    public void setConsentStatus(String v) { this.consentStatus = v; }
    public void setConsentToken(UUID v) { this.consentToken = v; }
    public void setConsentAcceptedAt(Instant v) { this.consentAcceptedAt = v; }
    public void setConsentExpiresAt(Instant v) { this.consentExpiresAt = v; }
    public void setConsentIpAddress(String v) { this.consentIpAddress = v; }
    public void setConsentTextVersion(String v) { this.consentTextVersion = v; }
    public void setCriteria(Map<String, Object> v) { this.criteria = v; }
    public void setUrgency(String v) { this.urgency = v; }
    public void setBudgetBracket(String v) { this.budgetBracket = v; }
    public void setBudgetApproval(String v) { this.budgetApproval = v; }
    public void setSituation(String v) { this.situation = v; }
    public void setStatus(String v) { this.status = v; }
    public void setCloseReason(String v) { this.closeReason = v; }
    public void setLastContactedAt(Instant v) { this.lastContactedAt = v; }
    public void setNextFollowUpAt(Instant v) { this.nextFollowUpAt = v; }
    public void setInternalNotes(String v) { this.internalNotes = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
