package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.UUID;

@Service
public class RequestVisitUseCase {

    private final VisitJpaRepository visitRepo;
    private final ListingJpaRepository listingRepo;

    public RequestVisitUseCase(VisitJpaRepository visitRepo, ListingJpaRepository listingRepo) {
        this.visitRepo = visitRepo;
        this.listingRepo = listingRepo;
    }

    public record Command(
        UUID listingId,
        UUID buyerUserId,
        UUID leadId,
        String mode,
        Instant startsAt,
        Instant endsAt,
        String notes
    ) {}

    public Visit execute(Command cmd) {
        if (cmd.startsAt() == null) {
            throw new DomainException("VALIDATION_ERROR", "A data da visita é obrigatória.");
        }
        if (cmd.startsAt().isBefore(Instant.now())) {
            throw new DomainException("VALIDATION_ERROR", "A data da visita não pode ser no passado.");
        }

        var listing = listingRepo.findById(cmd.listingId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        var visit = new Visit();
        visit.setListingId(listing.getId());
        visit.setAdvertiserId(listing.getAdvertiserId());
        visit.setBuyerUserId(cmd.buyerUserId());
        visit.setLeadId(cmd.leadId());
        visit.setMode(cmd.mode() != null ? cmd.mode() : "onsite");
        visit.setStartsAt(cmd.startsAt());
        visit.setEndsAt(cmd.endsAt());
        visit.setNotes(cmd.notes());
        visit.setStatus("requested");

        return visitRepo.save(visit);
    }
}
