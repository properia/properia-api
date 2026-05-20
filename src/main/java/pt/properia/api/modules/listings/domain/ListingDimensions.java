package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_dimensions", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingDimensions {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "usable_area_m2", precision = 10, scale = 2)
    private BigDecimal usableAreaM2;

    @Column(name = "gross_area_m2", precision = 10, scale = 2)
    private BigDecimal grossAreaM2;

    @Column(name = "private_area_m2", precision = 10, scale = 2)
    private BigDecimal privateAreaM2;

    @Column(name = "lot_area_m2", precision = 10, scale = 2)
    private BigDecimal lotAreaM2;

    @Column(name = "balcony_area_m2", precision = 10, scale = 2)
    private BigDecimal balconyAreaM2;

    @Column(name = "terrace_area_m2", precision = 10, scale = 2)
    private BigDecimal terraceAreaM2;

    @Column(name = "garden_area_m2", precision = 10, scale = 2)
    private BigDecimal gardenAreaM2;

    @Column(name = "ceiling_height_m", precision = 6, scale = 2)
    private BigDecimal ceilingHeightM;

    @Column(name = "rooms_total")
    private Integer roomsTotal;

    @Column
    private Integer bedrooms;

    @Column(precision = 4, scale = 1)
    private BigDecimal bathrooms;

    @Column
    private Integer suites;

    @Column(name = "living_rooms")
    private Integer livingRooms;

    @Column(name = "office_rooms")
    private Integer officeRooms;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingDimensions() {}

    public UUID getListingId() { return listingId; }
    public BigDecimal getUsableAreaM2() { return usableAreaM2; }
    public BigDecimal getGrossAreaM2() { return grossAreaM2; }
    public BigDecimal getPrivateAreaM2() { return privateAreaM2; }
    public BigDecimal getLotAreaM2() { return lotAreaM2; }
    public BigDecimal getBalconyAreaM2() { return balconyAreaM2; }
    public BigDecimal getTerraceAreaM2() { return terraceAreaM2; }
    public BigDecimal getGardenAreaM2() { return gardenAreaM2; }
    public BigDecimal getCeilingHeightM() { return ceilingHeightM; }
    public Integer getRoomsTotal() { return roomsTotal; }
    public Integer getBedrooms() { return bedrooms; }
    public BigDecimal getBathrooms() { return bathrooms; }
    public Integer getSuites() { return suites; }
    public Integer getLivingRooms() { return livingRooms; }
    public Integer getOfficeRooms() { return officeRooms; }

    public void setUsableAreaM2(BigDecimal v) { this.usableAreaM2 = v; }
    public void setGrossAreaM2(BigDecimal v) { this.grossAreaM2 = v; }
    public void setLotAreaM2(BigDecimal v) { this.lotAreaM2 = v; }
    public void setBedrooms(Integer v) { this.bedrooms = v; }
    public void setBathrooms(BigDecimal v) { this.bathrooms = v; }
    public void setSuites(Integer v) { this.suites = v; }
}
