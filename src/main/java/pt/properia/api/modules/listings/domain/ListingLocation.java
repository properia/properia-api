package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_location", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingLocation {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column
    private String country;

    @Column(name = "country_code", nullable = false)
    private String countryCode = "PT";

    @Column
    private String district;

    @Column
    private String municipality;

    @Column
    private String city;

    @Column
    private String parish;

    @Column
    private String neighborhood;

    @Column
    private String street;

    @Column(name = "street_number")
    private String streetNumber;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "full_address")
    private String fullAddress;

    @Column(name = "display_address")
    private String displayAddress;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(name = "location_precision")
    @ColumnTransformer(write = "?::properia.location_precision")
    private String locationPrecision;

    @Column(name = "hide_exact_location", nullable = false)
    private boolean hideExactLocation = false;

    @Column(name = "map_visibility_radius_m")
    private Integer mapVisibilityRadiusM;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingLocation() {}

    public UUID getListingId() { return listingId; }
    public String getCountry() { return country; }
    public String getCountryCode() { return countryCode; }
    public String getDistrict() { return district; }
    public String getMunicipality() { return municipality; }
    public String getCity() { return city; }
    public String getParish() { return parish; }
    public String getNeighborhood() { return neighborhood; }
    public String getStreet() { return street; }
    public String getStreetNumber() { return streetNumber; }
    public String getPostalCode() { return postalCode; }
    public String getFullAddress() { return fullAddress; }
    public String getDisplayAddress() { return displayAddress; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getLocationPrecision() { return locationPrecision; }
    public boolean isHideExactLocation() { return hideExactLocation; }
    public Integer getMapVisibilityRadiusM() { return mapVisibilityRadiusM; }
}
