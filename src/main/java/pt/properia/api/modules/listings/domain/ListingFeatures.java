package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_features", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingFeatures {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_flags", nullable = false, columnDefinition = "jsonb")
    private String featureFlags = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_tags", nullable = false, columnDefinition = "jsonb")
    private String featureTags = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "view_tags", nullable = false, columnDefinition = "jsonb")
    private String viewTags = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lifestyle_tags", nullable = false, columnDefinition = "jsonb")
    private String lifestyleTags = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "premium_signals", nullable = false, columnDefinition = "jsonb")
    private String premiumSignals = "[]";

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingFeatures() {}

    public UUID getListingId() { return listingId; }
    public String getFeatureFlags() { return featureFlags; }
    public String getFeatureTags() { return featureTags; }
    public String getViewTags() { return viewTags; }
    public String getLifestyleTags() { return lifestyleTags; }
    public String getPremiumSignals() { return premiumSignals; }
}
