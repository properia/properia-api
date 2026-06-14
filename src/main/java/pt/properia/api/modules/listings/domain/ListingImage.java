package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_images", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingImage {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private Integer position = 0;

    @Column(columnDefinition = "text")
    private String caption;

    @Column(columnDefinition = "jsonb", nullable = false)
    private String metadata = "{}";

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Getters
    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public String getUrl() { return url; }
    public Integer getPosition() { return position; }
    public String getCaption() { return caption; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }

    // Setters
    public void setId(UUID id) { this.id = id; }
    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setUrl(String url) { this.url = url; }
    public void setPosition(Integer position) { this.position = position; }
    public void setCaption(String caption) { this.caption = caption; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
