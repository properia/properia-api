package pt.properia.api.modules.team.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "advertiser_team_invites", schema = "properia")
public class AdvertiserTeamInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "advertiser_id", nullable = false)
    private UUID advertiserId;

    @Column(name = "invited_by_user_id", nullable = false)
    private UUID invitedByUserId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "membership_role", nullable = false)
    @ColumnTransformer(write = "?::properia.advertiser_membership_role")
    private String membershipRole;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by_user_id")
    private UUID acceptedByUserId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public AdvertiserTeamInvite() {}

    public UUID getId() { return id; }
    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getInvitedByUserId() { return invitedByUserId; }
    public String getEmail() { return email; }
    public String getMembershipRole() { return membershipRole; }
    public String getToken() { return token; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public UUID getAcceptedByUserId() { return acceptedByUserId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(UUID v) { this.id = v; }
    public void setAdvertiserId(UUID v) { this.advertiserId = v; }
    public void setInvitedByUserId(UUID v) { this.invitedByUserId = v; }
    public void setEmail(String v) { this.email = v; }
    public void setMembershipRole(String v) { this.membershipRole = v; }
    public void setToken(String v) { this.token = v; }
    public void setAcceptedAt(Instant v) { this.acceptedAt = v; }
    public void setAcceptedByUserId(UUID v) { this.acceptedByUserId = v; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public boolean isPending() {
        return acceptedAt == null && Instant.now().isBefore(expiresAt);
    }

    public String status() {
        if (acceptedAt != null) return "accepted";
        if (Instant.now().isAfter(expiresAt)) return "expired";
        return "pending";
    }
}
