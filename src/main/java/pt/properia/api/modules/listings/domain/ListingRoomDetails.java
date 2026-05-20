package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_room_details", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingRoomDetails {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "has_private_bathroom", nullable = false)
    private boolean hasPrivateBathroom = false;

    @Column(name = "bills_included", nullable = false)
    private boolean billsIncluded = false;

    @Column(name = "internet_included", nullable = false)
    private boolean internetIncluded = false;

    @Column(name = "has_shared_kitchen", nullable = false)
    private boolean hasSharedKitchen = true;

    @Column(name = "total_rooms_in_house")
    private Integer totalRoomsInHouse;

    @Column(name = "current_occupants")
    private Integer currentOccupants;

    @Column(name = "min_stay_months")
    private Integer minStayMonths;

    @Column(name = "couple_allowed", nullable = false)
    private boolean coupleAllowed = true;

    @Column(name = "is_exterior_room", nullable = false)
    private boolean isExteriorRoom = false;

    @Column(name = "house_rules_text", columnDefinition = "text")
    private String houseRulesText;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingRoomDetails() {}

    public UUID getListingId() { return listingId; }
    public boolean isHasPrivateBathroom() { return hasPrivateBathroom; }
    public boolean isBillsIncluded() { return billsIncluded; }
    public boolean isInternetIncluded() { return internetIncluded; }
    public boolean isHasSharedKitchen() { return hasSharedKitchen; }
    public Integer getTotalRoomsInHouse() { return totalRoomsInHouse; }
    public Integer getCurrentOccupants() { return currentOccupants; }
    public Integer getMinStayMonths() { return minStayMonths; }
    public boolean isCoupleAllowed() { return coupleAllowed; }
    public boolean isExteriorRoom() { return isExteriorRoom; }
    public String getHouseRulesText() { return houseRulesText; }
}
