package pt.properia.api.modules.team.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "advertiser_users", schema = "properia")
@IdClass(AdvertiserUserId.class)
public class AdvertiserUser {

    @Id
    @Column(name = "advertiser_id")
    private UUID advertiserId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "membership_role")
    @ColumnTransformer(write = "?::properia.advertiser_membership_role")
    private String membershipRole;

    @Column(name = "created_at")
    private Instant createdAt;

    public AdvertiserUser() {}

    public UUID getAdvertiserId() { return advertiserId; }
    public UUID getUserId() { return userId; }
    public String getMembershipRole() { return membershipRole; }
    public Instant getCreatedAt() { return createdAt; }

    public void setAdvertiserId(UUID v) { this.advertiserId = v; }
    public void setUserId(UUID v) { this.userId = v; }
    public void setMembershipRole(String v) { this.membershipRole = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
