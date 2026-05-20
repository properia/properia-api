package pt.properia.api.modules.crm.application.lead;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.Set;
import java.util.UUID;

@Service
public class UpdateLeadStageUseCase {

    private static final Set<String> VALID_STAGES =
        Set.of("new", "qualified", "contacted", "visit_scheduled", "proposal", "won", "lost");

    private final LeadJpaRepository leadRepo;

    public UpdateLeadStageUseCase(LeadJpaRepository leadRepo) {
        this.leadRepo = leadRepo;
    }

    public record Command(UUID leadId, UUID advertiserId, String stage, UUID assignedTo) {}

    public Lead execute(Command cmd) {
        if (!VALID_STAGES.contains(cmd.stage())) {
            throw new DomainException("VALIDATION_ERROR", "Etapa inválida: " + cmd.stage());
        }

        var lead = leadRepo.findByIdAndAdvertiserId(cmd.leadId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Lead não encontrado."));

        lead.setStage(cmd.stage());
        if (cmd.assignedTo() != null) {
            lead.setAssignedTo(cmd.assignedTo());
        }

        return leadRepo.save(lead);
    }
}
