package pt.properia.api.modules.crm.application.lead;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.UUID;

@Service
public class CreateLeadUseCase {

    private final LeadJpaRepository leadRepo;
    private final ListingJpaRepository listingRepo;

    public CreateLeadUseCase(LeadJpaRepository leadRepo, ListingJpaRepository listingRepo) {
        this.leadRepo = leadRepo;
        this.listingRepo = listingRepo;
    }

    public record Command(
        UUID listingId,
        UUID userId,
        String source,
        String intentType,
        String message,
        String contactName,
        String contactEmail,
        String contactPhone,
        String metadataJson
    ) {}

    public Lead execute(Command cmd) {
        var listing = listingRepo.findById(cmd.listingId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        // Deduplicação: um comprador autenticado = um lead por imóvel. Se já existe lead
        // para (imóvel, comprador), reutiliza-o (preenchendo só contactos em falta) em vez
        // de criar duplicados quando o mesmo comprador chega por vários canais
        // (formulário + chat + pedido de visita). Leads anónimos (sem userId) não deduplicam.
        if (cmd.userId() != null) {
            var existing = leadRepo.findFirstByListingIdAndUserIdOrderByCreatedAtAsc(cmd.listingId(), cmd.userId());
            if (existing.isPresent()) {
                var lead = existing.get();
                boolean dirty = false;
                if (isBlank(lead.getContactName()) && !isBlank(cmd.contactName())) { lead.setContactName(cmd.contactName()); dirty = true; }
                if (isBlank(lead.getContactEmail()) && !isBlank(cmd.contactEmail())) { lead.setContactEmail(cmd.contactEmail()); dirty = true; }
                if (isBlank(lead.getContactPhone()) && !isBlank(cmd.contactPhone())) { lead.setContactPhone(cmd.contactPhone()); dirty = true; }
                return dirty ? leadRepo.save(lead) : lead;
            }
        }

        var lead = new Lead();
        lead.setListingId(listing.getId());
        lead.setAdvertiserId(listing.getAdvertiserId());
        lead.setUserId(cmd.userId());
        lead.setSource(cmd.source() != null ? cmd.source() : "listing_detail");
        lead.setIntentType(cmd.intentType() != null ? cmd.intentType() : "buy");
        lead.setMessage(cmd.message());
        lead.setContactName(cmd.contactName());
        lead.setContactEmail(cmd.contactEmail());
        lead.setContactPhone(cmd.contactPhone());
        lead.setMetadata(cmd.metadataJson() != null ? cmd.metadataJson() : "{}");

        return leadRepo.save(lead);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
