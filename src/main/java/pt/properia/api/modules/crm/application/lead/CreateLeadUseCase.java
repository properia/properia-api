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
}
