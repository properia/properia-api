package pt.properia.api.modules.crm.application.lead;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.UUID;

@Service
public class CreateLeadUseCase {

    private final LeadJpaRepository leadRepo;
    private final ListingJpaRepository listingRepo;
    private final JdbcClient jdbc;

    public CreateLeadUseCase(LeadJpaRepository leadRepo, ListingJpaRepository listingRepo, JdbcClient jdbc) {
        this.leadRepo = leadRepo;
        this.listingRepo = listingRepo;
        this.jdbc = jdbc;
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

    @Transactional
    public Lead execute(Command cmd) {
        var listing = listingRepo.findById(cmd.listingId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        // Deduplicação: um comprador autenticado = um lead por imóvel. Se já existe lead
        // para (imóvel, comprador), reutiliza-o (preenchendo só contactos em falta) em vez
        // de criar duplicados quando o mesmo comprador chega por vários canais
        // (formulário + chat + pedido de visita). Leads anónimos (sem userId) não deduplicam.
        if (cmd.userId() != null) {
            // Serializa o find-then-insert por (imóvel, comprador) com um advisory lock
            // transacional. Sem isto, dois canais concorrentes passavam ambos o findFirst
            // e criavam leads duplicados (correção #5). O lock é libertado no commit.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:a), hashtext(:b))")
                .param("a", cmd.listingId().toString())
                .param("b", cmd.userId().toString())
                .query((rs, n) -> null)
                .optional();

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
