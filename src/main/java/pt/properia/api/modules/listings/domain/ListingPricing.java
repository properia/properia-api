package pt.properia.api.modules.listings.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_pricing", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class ListingPricing {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "list_price", precision = 14, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "rental_price", precision = 14, scale = 2)
    private BigDecimal rentalPrice;

    @Column(name = "price_currency", nullable = false)
    private String priceCurrency = "EUR";

    @Column(name = "price_period", nullable = false)
    @ColumnTransformer(write = "?::properia.price_period")
    private String pricePeriod = "sale";

    @Column(name = "condo_fee", precision = 12, scale = 2)
    private BigDecimal condoFee;

    @Column(name = "property_tax_annual", precision = 12, scale = 2)
    private BigDecimal propertyTaxAnnual;

    @Column(name = "municipal_tax_estimate", precision = 12, scale = 2)
    private BigDecimal municipalTaxEstimate;

    @Column(name = "maintenance_cost_estimate", precision = 12, scale = 2)
    private BigDecimal maintenanceCostEstimate;

    @Column(name = "price_per_m2", precision = 12, scale = 2)
    private BigDecimal pricePerM2;

    @Column(nullable = false)
    private boolean negotiable = false;

    @Column(name = "accepts_financing", nullable = false)
    private boolean acceptsFinancing = false;

    @Column(name = "deposit_required", precision = 12, scale = 2)
    private BigDecimal depositRequired;

    @Column(name = "broker_commission_percentage", precision = 5, scale = 2)
    private BigDecimal brokerCommissionPercentage;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ListingPricing() {}

    public UUID getListingId() { return listingId; }
    public BigDecimal getListPrice() { return listPrice; }
    public BigDecimal getRentalPrice() { return rentalPrice; }
    public String getPriceCurrency() { return priceCurrency; }
    public String getPricePeriod() { return pricePeriod; }
    public BigDecimal getCondoFee() { return condoFee; }
    public BigDecimal getPropertyTaxAnnual() { return propertyTaxAnnual; }
    public BigDecimal getMunicipalTaxEstimate() { return municipalTaxEstimate; }
    public BigDecimal getMaintenanceCostEstimate() { return maintenanceCostEstimate; }
    public BigDecimal getPricePerM2() { return pricePerM2; }
    public boolean isNegotiable() { return negotiable; }
    public boolean isAcceptsFinancing() { return acceptsFinancing; }
    public BigDecimal getDepositRequired() { return depositRequired; }
    public BigDecimal getBrokerCommissionPercentage() { return brokerCommissionPercentage; }

    public void setListPrice(BigDecimal listPrice) { this.listPrice = listPrice; }
    public void setRentalPrice(BigDecimal rentalPrice) { this.rentalPrice = rentalPrice; }
    public void setPricePeriod(String pricePeriod) { this.pricePeriod = pricePeriod; }
    public void setCondoFee(BigDecimal condoFee) { this.condoFee = condoFee; }
    public void setPropertyTaxAnnual(BigDecimal propertyTaxAnnual) { this.propertyTaxAnnual = propertyTaxAnnual; }
    public void setNegotiable(boolean negotiable) { this.negotiable = negotiable; }
    public void setAcceptsFinancing(boolean acceptsFinancing) { this.acceptsFinancing = acceptsFinancing; }
    public void setDepositRequired(BigDecimal depositRequired) { this.depositRequired = depositRequired; }
    public void setBrokerCommissionPercentage(BigDecimal brokerCommissionPercentage) { this.brokerCommissionPercentage = brokerCommissionPercentage; }
}
