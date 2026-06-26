package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class RequestVisitUseCase {

    private static final Duration DEFAULT_DURATION = Duration.ofMinutes(60);
    private static final Duration CONFLICT_SEARCH_MARGIN = Duration.ofHours(4);
    private static final Set<String> ACTIVE_STATUSES = Set.of("requested", "confirmed");

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

        if (hasConflict(listing.getAdvertiserId(), cmd.startsAt(), cmd.endsAt(), null)) {
            throw new DomainException("VALIDATION_ERROR", "Já existe outra visita agendada nesse horário.", 409);
        }

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

    /** Verifica se já existe outra visita ativa do mesmo anunciante a sobrepor-se ao horário dado. */
    public boolean hasConflict(UUID advertiserId, Instant startsAt, Instant endsAt, UUID excludeVisitId) {
        var effectiveEnd = endsAt != null ? endsAt : startsAt.plus(DEFAULT_DURATION);
        var windowFrom = startsAt.minus(CONFLICT_SEARCH_MARGIN);
        var windowTo = effectiveEnd.plus(CONFLICT_SEARCH_MARGIN);

        var candidates = visitRepo.findByAdvertiserIdAndStatusInAndStartsAtBetween(
            advertiserId, ACTIVE_STATUSES, windowFrom, windowTo);

        for (var existing : candidates) {
            if (excludeVisitId != null && existing.getId().equals(excludeVisitId)) {
                continue;
            }
            var existingEnd = existing.getEndsAt() != null
                ? existing.getEndsAt()
                : existing.getStartsAt().plus(DEFAULT_DURATION);
            var overlaps = startsAt.isBefore(existingEnd) && existing.getStartsAt().isBefore(effectiveEnd);
            if (overlaps) {
                return true;
            }
        }
        return false;
    }
}
