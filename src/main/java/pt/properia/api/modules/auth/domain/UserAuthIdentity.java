package pt.properia.api.modules.auth.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_auth_identities", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class UserAuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.auth_provider")
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "password_algorithm")
    @ColumnTransformer(write = "?::properia.password_algorithm")
    private String passwordAlgorithm;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAuthIdentity() {}

    public UserAuthIdentity(UUID userId, String provider, String providerUserId,
                            String email, boolean emailVerified,
                            String passwordHash, String passwordAlgorithm) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.emailVerified = emailVerified;
        this.passwordHash = passwordHash;
        this.passwordAlgorithm = passwordAlgorithm;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getPasswordHash() { return passwordHash; }
    public String getPasswordAlgorithm() { return passwordAlgorithm; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void setLastLoginAt(Instant ts) { this.lastLoginAt = ts; }
    public void setPasswordHash(String hash) { this.passwordHash = hash; }
    public void setPasswordAlgorithm(String algo) { this.passwordAlgorithm = algo; }
    public void setEmailVerified(boolean verified) { this.emailVerified = verified; }
}
