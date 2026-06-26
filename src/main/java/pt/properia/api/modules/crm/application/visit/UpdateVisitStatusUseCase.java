package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.application.lead.LeadStageAdvancer;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.Set;
import java.util.UUID;

@Service
public class UpdateVisitStatusUseCase {

    private static final Set<String> VALID_STATUSES =
        Set.of("requested", "confirmed", "completed", "cancelled", "no_show", "expired");

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "no_show");

    private final VisitJpaRepository visitRepo;
    private final LeadStageAdvancer leadStageAdvancer;

    public UpdateVisitStatusUseCase(VisitJpaRepository visitRepo, LeadStageAdvancer leadStageAdvancer) {
        this.visitRepo = visitRepo;
        this.leadStageAdvancer = leadStageAdvancer;
    }

    public record Command(UUID visitId, UUID advertiserId, String status, String meetingUrl) {}

    public Visit execute(Command cmd) {
        if (!VALID_STATUSES.contains(cmd.status())) {
            throw new DomainException("VALIDATION_ERROR", "Estado inválido: " + cmd.status());
        }

        var visit = visitRepo.findByIdAndAdvertiserId(cmd.visitId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Visita não encontrada."));

        if (!cmd.status().equals(visit.getStatus()) && TERMINAL_STATUSES.contains(visit.getStatus())) {
            throw new DomainException("VALIDATION_ERROR",
                "Esta visita já está fechada e não pode mudar de estado. Agenda uma nova visita.", 422);
        }

        visit.setStatus(cmd.status());
        if (cmd.meetingUrl() != null && !cmd.meetingUrl().isBlank()) {
            visit.setMeetingUrl(cmd.meetingUrl());
        }

        var saved = visitRepo.save(visit);

        // Confirmar uma visita coloca o lead em 'visit_scheduled' (forward-only).
        // Cobre todos os caminhos de confirmação: endpoint /confirm, PATCH status e
        // a criação manual pelo consultor.
        if ("confirmed".equals(cmd.status())) {
            leadStageAdvancer.advanceForward(saved.getLeadId(), saved.getAdvertiserId(), "visit_scheduled");
        }

        return saved;
    }
}
