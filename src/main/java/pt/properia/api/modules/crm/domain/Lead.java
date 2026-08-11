package pt.properia.api.modules.crm.domain;

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

@Entity
@Table(name = "leads", schema = "properia")
@EntityListeners(AuditingEntityListener.class)
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Nulo apenas em leads de angariação (lead_type = OWNER): o imóvel ainda não
     * existe. É preenchido quando o consultor qualifica o lead e promove o
     * snapshot a anúncio. Para leads de comprador continua obrigatório — a
     * garantia é reposta pelo CHECK leads_buyer_requires_listing (V78) e por
     * {@link #validateInvariants()}.
     */
    @Column(name = "listing_id")
    private UUID listingId;

    @Column(name = "user_id")
    private UUID userId;

    /**
     * Nulo enquanto um lead de angariação não tiver encaminhamento definido para
     * a zona (fila por encaminhar). Nenhuma query de CRM o apanha nesse estado:
     * todas filtram por advertiser_id e NULL nunca corresponde.
     */
    @Column(name = "advertiser_id")
    private UUID advertiserId;

    @Column(name = "lead_type", nullable = false)
    @ColumnTransformer(write = "?::properia.lead_type")
    @Convert(converter = LeadType.JpaConverter.class)
    private LeadType leadType = LeadType.BUYER;

    /**
     * O formulário público de angariação aceita contactos sem qualquer prova.
     * Enquanto isto for false, o contacto não deve ser tratado como fiável nem
     * consumir tempo da equipa comercial.
     */
    @Column(name = "contact_verified", nullable = false)
    private boolean contactVerified = false;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.lead_source")
    private String source;

    @Column(nullable = false)
    @ColumnTransformer(write = "?::properia.lead_stage")
    private String stage = "new";

    @Column(name = "intent_type", nullable = false)
    @ColumnTransformer(write = "?::properia.intent_type")
    private String intentType = "buy";

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Lead() {}

    /**
     * Lead de angariação: proprietário captado sem imóvel associado.
     *
     * @param advertiserId anunciante encaminhado, ou null se ainda não houver
     *                     regra de encaminhamento para a zona.
     */
    public static Lead forOwner(UUID advertiserId, String source, String contactName,
                               String contactEmail, String contactPhone,
                               String message, String metadataJson) {
        var lead = new Lead();
        lead.leadType = LeadType.OWNER;
        lead.listingId = null;
        lead.advertiserId = advertiserId;
        lead.source = source != null ? source : "owner_landing";
        // 'valuation' já existia no enum intent_type desde o schema inicial e
        // descreve exatamente esta intenção — não é preciso alargar o enum.
        lead.intentType = "valuation";
        lead.stage = "new";
        lead.contactName = contactName;
        lead.contactEmail = contactEmail;
        lead.contactPhone = contactPhone;
        lead.message = message;
        lead.metadata = metadataJson != null ? metadataJson : "{}";
        lead.contactVerified = false;
        lead.validateInvariants();
        return lead;
    }

    /**
     * Invariantes que o CHECK da V78 também impõe na base de dados. Duplicadas
     * aqui para falhar cedo, com uma mensagem útil, em vez de rebentar com um
     * erro de constraint opaco no flush.
     */
    public void validateInvariants() {
        if (leadType == null) {
            throw new IllegalStateException("lead_type é obrigatório.");
        }
        if (leadType == LeadType.BUYER) {
            if (listingId == null) {
                throw new IllegalStateException("Um lead de comprador exige um imóvel associado.");
            }
            if (advertiserId == null) {
                throw new IllegalStateException("Um lead de comprador exige um anunciante associado.");
            }
        }
        if (contactEmail == null && contactPhone == null) {
            throw new IllegalStateException("Um lead exige pelo menos um contacto (email ou telefone).");
        }
    }

    public boolean isOwnerLead() {
        return leadType == LeadType.OWNER;
    }

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getUserId() { return userId; }
    public UUID getAdvertiserId() { return advertiserId; }
    public LeadType getLeadType() { return leadType; }
    public boolean isContactVerified() { return contactVerified; }
    public String getSource() { return source; }
    public String getStage() { return stage; }
    public String getIntentType() { return intentType; }
    public String getMessage() { return message; }
    public String getContactName() { return contactName; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public BigDecimal getScore() { return score; }
    public UUID getAssignedTo() { return assignedTo; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setListingId(UUID listingId) { this.listingId = listingId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setAdvertiserId(UUID advertiserId) { this.advertiserId = advertiserId; }
    public void setLeadType(LeadType leadType) { this.leadType = leadType; }
    public void setContactVerified(boolean contactVerified) { this.contactVerified = contactVerified; }
    public void setSource(String source) { this.source = source; }
    public void setStage(String stage) { this.stage = stage; }
    public void setIntentType(String intentType) { this.intentType = intentType; }
    public void setMessage(String message) { this.message = message; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public void setAssignedTo(UUID assignedTo) { this.assignedTo = assignedTo; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
