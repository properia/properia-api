package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_commercial", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingCommercial {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "exclusive_listing", nullable = false)
    private boolean exclusiveListing = false;

    @Column(name = "online_visit_available", nullable = false)
    private boolean onlineVisitAvailable = false;

    @Column(name = "visit_booking_enabled", nullable = false)
    private boolean visitBookingEnabled = true;

    @Column(name = "youtube_tour_url")
    private String youtubeTourUrl;

    @Column(name = "virtual_tour_url")
    private String virtualTourUrl;

    @Column(name = "virtual_tour_status")
    private String virtualTourStatus;

    @Column(name = "virtual_tour_render_id")
    private String virtualTourRenderId;

    @Column(name = "virtual_tour_generated_at")
    private java.time.Instant virtualTourGeneratedAt;

    @Column(name = "floorplan_url")
    private String floorplanUrl;

    @Column(name = "show_phone", nullable = false)
    private boolean showPhone = true;

    @Column(name = "show_chat", nullable = false)
    private boolean showChat = true;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingCommercial() {}

    public UUID getListingId() { return listingId; }
    public boolean isExclusiveListing() { return exclusiveListing; }
    public boolean isOnlineVisitAvailable() { return onlineVisitAvailable; }
    public boolean isVisitBookingEnabled() { return visitBookingEnabled; }
    public String getYoutubeTourUrl() { return youtubeTourUrl; }
    public String getVirtualTourUrl() { return virtualTourUrl; }
    public String getVirtualTourStatus() { return virtualTourStatus; }
    public String getVirtualTourRenderId() { return virtualTourRenderId; }
    public java.time.Instant getVirtualTourGeneratedAt() { return virtualTourGeneratedAt; }
    public String getFloorplanUrl() { return floorplanUrl; }

    public void setVirtualTourUrl(String url) { this.virtualTourUrl = url; }
    public void setVirtualTourStatus(String status) { this.virtualTourStatus = status; }
    public void setVirtualTourRenderId(String renderId) { this.virtualTourRenderId = renderId; }
    public void setVirtualTourGeneratedAt(java.time.Instant ts) { this.virtualTourGeneratedAt = ts; }
    public boolean isShowPhone() { return showPhone; }
    public boolean isShowChat() { return showChat; }
}
