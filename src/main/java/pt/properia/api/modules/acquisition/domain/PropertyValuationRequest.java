package pt.properia.api.modules.acquisition.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pedido de avaliação submetido na landing pública de angariação (/vender).
 *
 * Snapshot imutável do que o proprietário declarou + a estimativa que lhe foi
 * efetivamente mostrada no ecrã. 1:1 com um {@code Lead} de tipo OWNER, que é
 * quem carrega o pipeline comercial (etapa, atribuição, SLA, notas).
 *
 * Ver V79__property_valuation_requests.sql para o racional do desenho.
 */
@Entity
@Table(name = "property_valuation_requests", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class PropertyValuationRequest {

    /** Janela de validade do código de verificação de contacto. */
    public static final long CODE_TTL_SECONDS = 600;

    /** Intervalo mínimo entre reenvios do código, para não servir de amplificador de spam. */
    public static final long CODE_RESEND_COOLDOWN_SECONDS = 60;

    /** Tentativas falhadas antes de exigir um código novo. */
    public static final int CODE_MAX_FAILED_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "public_token", nullable = false, updatable = false)
    private String publicToken;

    // ── Snapshot do imóvel ────────────────────────────────────────────────────

    @Column(name = "address_raw")
    private String addressRaw;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "district")
    private String district;

    @Column(name = "municipality")
    private String municipality;

    @Column(name = "parish")
    private String parish;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "property_type", nullable = false)
    @ColumnTransformer(write = "?::properia.property_type")
    private String propertyType;

    @Column(name = "bedrooms")
    private Integer bedrooms;

    @Column(name = "usable_area_m2", precision = 10, scale = 2)
    private BigDecimal usableAreaM2;

    @Column(name = "condition_status")
    @ColumnTransformer(write = "?::properia.condition_status")
    private String conditionStatus;

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "has_elevator")
    private Boolean hasElevator;

    @Column(name = "energy_rating")
    private String energyRating;

    // ── Qualificação comercial ────────────────────────────────────────────────

    @Column(name = "selling_horizon")
    private String sellingHorizon;

    @Column(name = "has_agency")
    private Boolean hasAgency;

    @Column(name = "motivation", columnDefinition = "text")
    private String motivation;

    // ── Estimativa apresentada ────────────────────────────────────────────────

    @Column(name = "estimate_min", precision = 12, scale = 2)
    private BigDecimal estimateMin;

    @Column(name = "estimate_max", precision = 12, scale = 2)
    private BigDecimal estimateMax;

    @Column(name = "estimate_ppm2", precision = 10, scale = 2)
    private BigDecimal estimatePpm2;

    @Column(name = "estimate_confidence")
    private String estimateConfidence;

    @Column(name = "estimate_sample_size", nullable = false)
    private int estimateSampleSize = 0;

    @Column(name = "estimate_source")
    private String estimateSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "estimate_inputs", nullable = false, columnDefinition = "jsonb")
    private String estimateInputs = "{}";

    // ── Consentimento RGPD ────────────────────────────────────────────────────

    @Column(name = "consent_granted", nullable = false)
    private boolean consentGranted = false;

    @Column(name = "consent_text", nullable = false, columnDefinition = "text")
    private String consentText;

    @Column(name = "consent_ip", columnDefinition = "inet")
    @ColumnTransformer(write = "?::inet")
    private String consentIp;

    @Column(name = "consent_at")
    private Instant consentAt;

    @Column(name = "marketing_consent", nullable = false)
    private boolean marketingConsent = false;

    // ── Verificação de contacto (OTP) ─────────────────────────────────────────

    @Column(name = "contact_code_hash")
    private String contactCodeHash;

    @Column(name = "contact_code_expires_at")
    private Instant contactCodeExpiresAt;

    @Column(name = "contact_code_last_sent_at")
    private Instant contactCodeLastSentAt;

    @Column(name = "contact_code_failed_attempts", nullable = false)
    private int contactCodeFailedAttempts = 0;

    @Column(name = "contact_verified_at")
    private Instant contactVerifiedAt;

    // ── Proveniência ──────────────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "utm", nullable = false, columnDefinition = "jsonb")
    private String utm = "{}";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PropertyValuationRequest() {}

    // ── Comportamento ─────────────────────────────────────────────────────────

    /** Regista um código emitido. O hash entra já calculado — a entidade nunca vê o código em claro. */
    public void issueContactCode(String codeHash, Instant now) {
        this.contactCodeHash = codeHash;
        this.contactCodeExpiresAt = now.plusSeconds(CODE_TTL_SECONDS);
        this.contactCodeLastSentAt = now;
        this.contactCodeFailedAttempts = 0;
    }

    public boolean isContactVerified() {
        return contactVerifiedAt != null;
    }

    /** Segundos que faltam antes de ser possível reenviar; 0 se já se pode. */
    public long resendCooldownSeconds(Instant now) {
        if (contactCodeLastSentAt == null) return 0;
        var elapsed = now.getEpochSecond() - contactCodeLastSentAt.getEpochSecond();
        return Math.max(0, CODE_RESEND_COOLDOWN_SECONDS - elapsed);
    }

    public boolean isCodeExpired(Instant now) {
        return contactCodeExpiresAt == null || contactCodeExpiresAt.isBefore(now);
    }

    public boolean hasExhaustedAttempts() {
        return contactCodeFailedAttempts >= CODE_MAX_FAILED_ATTEMPTS;
    }

    public void registerFailedAttempt() {
        this.contactCodeFailedAttempts++;
    }

    /** Consome o código: marca verificado e invalida o hash para não poder ser reutilizado. */
    public void markContactVerified(Instant now) {
        this.contactVerifiedAt = now;
        this.contactCodeHash = null;
        this.contactCodeExpiresAt = null;
        this.contactCodeFailedAttempts = 0;
    }

    // ── Acessores ─────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public String getPublicToken() { return publicToken; }
    public String getAddressRaw() { return addressRaw; }
    public String getPostalCode() { return postalCode; }
    public String getDistrict() { return district; }
    public String getMunicipality() { return municipality; }
    public String getParish() { return parish; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getPropertyType() { return propertyType; }
    public Integer getBedrooms() { return bedrooms; }
    public BigDecimal getUsableAreaM2() { return usableAreaM2; }
    public String getConditionStatus() { return conditionStatus; }
    public Integer getFloorNumber() { return floorNumber; }
    public Boolean getHasElevator() { return hasElevator; }
    public String getEnergyRating() { return energyRating; }
    public String getSellingHorizon() { return sellingHorizon; }
    public Boolean getHasAgency() { return hasAgency; }
    public String getMotivation() { return motivation; }
    public BigDecimal getEstimateMin() { return estimateMin; }
    public BigDecimal getEstimateMax() { return estimateMax; }
    public BigDecimal getEstimatePpm2() { return estimatePpm2; }
    public String getEstimateConfidence() { return estimateConfidence; }
    public int getEstimateSampleSize() { return estimateSampleSize; }
    public String getEstimateSource() { return estimateSource; }
    public String getEstimateInputs() { return estimateInputs; }
    public boolean isConsentGranted() { return consentGranted; }
    public String getConsentText() { return consentText; }
    public String getConsentIp() { return consentIp; }
    public Instant getConsentAt() { return consentAt; }
    public boolean isMarketingConsent() { return marketingConsent; }
    public String getContactCodeHash() { return contactCodeHash; }
    public Instant getContactCodeExpiresAt() { return contactCodeExpiresAt; }
    public Instant getContactCodeLastSentAt() { return contactCodeLastSentAt; }
    public int getContactCodeFailedAttempts() { return contactCodeFailedAttempts; }
    public Instant getContactVerifiedAt() { return contactVerifiedAt; }
    public String getUtm() { return utm; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public void setPublicToken(String publicToken) { this.publicToken = publicToken; }
    public void setAddressRaw(String addressRaw) { this.addressRaw = addressRaw; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public void setDistrict(String district) { this.district = district; }
    public void setMunicipality(String municipality) { this.municipality = municipality; }
    public void setParish(String parish) { this.parish = parish; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
    public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }
    public void setUsableAreaM2(BigDecimal usableAreaM2) { this.usableAreaM2 = usableAreaM2; }
    public void setConditionStatus(String conditionStatus) { this.conditionStatus = conditionStatus; }
    public void setFloorNumber(Integer floorNumber) { this.floorNumber = floorNumber; }
    public void setHasElevator(Boolean hasElevator) { this.hasElevator = hasElevator; }
    public void setEnergyRating(String energyRating) { this.energyRating = energyRating; }
    public void setSellingHorizon(String sellingHorizon) { this.sellingHorizon = sellingHorizon; }
    public void setHasAgency(Boolean hasAgency) { this.hasAgency = hasAgency; }
    public void setMotivation(String motivation) { this.motivation = motivation; }
    public void setEstimateMin(BigDecimal estimateMin) { this.estimateMin = estimateMin; }
    public void setEstimateMax(BigDecimal estimateMax) { this.estimateMax = estimateMax; }
    public void setEstimatePpm2(BigDecimal estimatePpm2) { this.estimatePpm2 = estimatePpm2; }
    public void setEstimateConfidence(String estimateConfidence) { this.estimateConfidence = estimateConfidence; }
    public void setEstimateSampleSize(int estimateSampleSize) { this.estimateSampleSize = estimateSampleSize; }
    public void setEstimateSource(String estimateSource) { this.estimateSource = estimateSource; }
    public void setEstimateInputs(String estimateInputs) { this.estimateInputs = estimateInputs; }
    public void setConsentGranted(boolean consentGranted) { this.consentGranted = consentGranted; }
    public void setConsentText(String consentText) { this.consentText = consentText; }
    public void setConsentIp(String consentIp) { this.consentIp = consentIp; }
    public void setConsentAt(Instant consentAt) { this.consentAt = consentAt; }
    public void setMarketingConsent(boolean marketingConsent) { this.marketingConsent = marketingConsent; }
    public void setUtm(String utm) { this.utm = utm; }
}
