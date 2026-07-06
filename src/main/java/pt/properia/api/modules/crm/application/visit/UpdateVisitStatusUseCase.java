package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.application.lead.LeadStageAdvancer;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UpdateVisitStatusUseCase {

    private static final Set<String> VALID_STATUSES =
        Set.of("requested", "waitlist", "confirmed", "completed", "cancelled", "no_show", "expired");

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "no_show");

    // Máquina de estados das visitas: de cada estado, para que estados se pode transitar.
    // Terminais (completed/cancelled/no_show) não têm saída. 'expired' é um estado neutro de
    // "precisa de revisão" que o consultor resolve para o desfecho real.
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "requested", Set.of("confirmed", "cancelled", "expired"),
        "waitlist",  Set.of("confirmed", "cancelled", "expired"),
        "confirmed", Set.of("completed", "no_show", "cancelled", "expired"),
        "expired",   Set.of("completed", "no_show", "cancelled"),
        "completed", Set.of(),
        "cancelled", Set.of(),
        "no_show",   Set.of()
    );

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

        var current = visit.getStatus();
        var target = cmd.status();
        if (!target.equals(current)) {
            if (TERMINAL_STATUSES.contains(current)) {
                throw new DomainException("CONFLICT",
                    "Esta visita já está fechada e não pode mudar de estado. Agenda uma nova visita.", 409);
            }
            if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
                throw new DomainException("CONFLICT",
                    "Transição de estado inválida: " + current + " → " + target + ".", 409);
            }
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
