package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.Set;
import java.util.UUID;

@Service
public class UpdateVisitStatusUseCase {

    private static final Set<String> VALID_STATUSES =
        Set.of("requested", "confirmed", "completed", "cancelled", "no_show");

    private final VisitJpaRepository visitRepo;

    public UpdateVisitStatusUseCase(VisitJpaRepository visitRepo) {
        this.visitRepo = visitRepo;
    }

    public record Command(UUID visitId, UUID advertiserId, String status, String meetingUrl) {}

    public Visit execute(Command cmd) {
        if (!VALID_STATUSES.contains(cmd.status())) {
            throw new DomainException("VALIDATION_ERROR", "Estado inválido: " + cmd.status());
        }

        var visit = visitRepo.findByIdAndAdvertiserId(cmd.visitId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Visita não encontrada."));

        visit.setStatus(cmd.status());
        if (cmd.meetingUrl() != null && !cmd.meetingUrl().isBlank()) {
            visit.setMeetingUrl(cmd.meetingUrl());
        }

        return visitRepo.save(visit);
    }
}
