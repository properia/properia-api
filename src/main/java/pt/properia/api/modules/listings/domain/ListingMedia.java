package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_media", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "media_type", nullable = false)
    @ColumnTransformer(write = "?::properia.media_type")
    private String mediaType;

    @Column(name = "source_type", nullable = false)
    @ColumnTransformer(write = "?::properia.media_source_type")
    private String sourceType;

    @Column(nullable = false)
    private String url;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "is_cover", nullable = false)
    private boolean isCover = false;

    @Column
    private String caption;

    @Column(name = "room_hint")
    @ColumnTransformer(write = "?::properia.room_hint")
    private String roomHint = "other";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ListingMedia() {}

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public String getMediaType() { return mediaType; }
    public String getSourceType() { return sourceType; }
    public String getUrl() { return url; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getSortOrder() { return sortOrder; }
    public boolean isCover() { return isCover; }
    public String getCaption() { return caption; }
    public String getRoomHint() { return roomHint; }
    public Instant getCreatedAt() { return createdAt; }
}
