package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_commercial_details", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingCommercialDetails {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "has_shopfront", nullable = false)
    private boolean hasShopfront = false;

    @Column(name = "street_visibility")
    @ColumnTransformer(write = "?::properia.street_visibility")
    private String streetVisibility;

    @Column(name = "internal_floors")
    private Integer internalFloors;

    @Column(name = "has_vehicle_access", nullable = false)
    private boolean hasVehicleAccess = false;

    @Column(name = "permitted_use")
    private String permittedUse;

    @Column(name = "has_flue_pipe", nullable = false)
    private boolean hasFluePipe = false;

    @Column(name = "has_extraction_system", nullable = false)
    private boolean hasExtractionSystem = false;

    @Column(name = "has_wc", nullable = false)
    private boolean hasWc = false;

    @Column(name = "has_kitchenette", nullable = false)
    private boolean hasKitchenette = false;

    @Column(name = "has_outdoor_seating_potential", nullable = false)
    private boolean hasOutdoorSeatingPotential = false;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingCommercialDetails() {}

    public UUID getListingId() { return listingId; }
    public boolean isHasShopfront() { return hasShopfront; }
    public String getStreetVisibility() { return streetVisibility; }
    public Integer getInternalFloors() { return internalFloors; }
    public boolean isHasVehicleAccess() { return hasVehicleAccess; }
    public String getPermittedUse() { return permittedUse; }
    public boolean isHasFluePipe() { return hasFluePipe; }
    public boolean isHasExtractionSystem() { return hasExtractionSystem; }
    public boolean isHasWc() { return hasWc; }
    public boolean isHasKitchenette() { return hasKitchenette; }
    public boolean isHasOutdoorSeatingPotential() { return hasOutdoorSeatingPotential; }
}
