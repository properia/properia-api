package pt.properia.api.modules.crm.application.lead;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UpdateLeadStageUseCase {

    private static final Set<String> VALID_STAGES =
        Set.of("new", "contacted", "qualified", "visit_scheduled", "proposal", "won", "lost");

    private static final Set<String> TERMINAL_STAGES = Set.of("won", "lost");

    private static final Set<String> VALID_CLOSE_REASONS =
        Set.of("price", "financing", "timing", "location", "competitor", "documentation", "inventory_unavailable", "other");

    private final LeadJpaRepository leadRepo;
    private final ObjectMapper objectMapper;

    public UpdateLeadStageUseCase(LeadJpaRepository leadRepo, ObjectMapper objectMapper) {
        this.leadRepo = leadRepo;
        this.objectMapper = objectMapper;
    }

    public record Command(UUID leadId, UUID advertiserId, String stage, UUID assignedTo, String closeReason) {}

    public Lead execute(Command cmd) {
        var lead = leadRepo.findByIdAndAdvertiserId(cmd.leadId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Lead não encontrado."));

        var changingStage = cmd.stage() != null && !cmd.stage().equals(lead.getStage());

        if (changingStage) {
            if (!VALID_STAGES.contains(cmd.stage())) {
                throw new DomainException("VALIDATION_ERROR", "Etapa inválida: " + cmd.stage());
            }
            if (TERMINAL_STAGES.contains(lead.getStage())) {
                throw new DomainException("VALIDATION_ERROR",
                    "Este lead já está fechado e não pode ser reaberto.", 422);
            }
        }

        var targetStage = cmd.stage() != null ? cmd.stage() : lead.getStage();
        if (changingStage && TERMINAL_STAGES.contains(targetStage)) {
            var reason = cmd.closeReason() != null ? cmd.closeReason() : extractCloseReason(lead.getMetadata());
            if (reason == null || reason.isBlank()) {
                throw new DomainException("VALIDATION_ERROR",
                    "Indica o motivo do desfecho antes de fechar o lead.", 422);
            }
            if (!VALID_CLOSE_REASONS.contains(reason)) {
                throw new DomainException("VALIDATION_ERROR", "Motivo de desfecho inválido.", 422);
            }
        }

        if (cmd.stage() != null) {
            lead.setStage(cmd.stage());
        }
        if (cmd.assignedTo() != null) {
            lead.setAssignedTo(cmd.assignedTo());
        }
        if (cmd.closeReason() != null) {
            lead.setMetadata(mergeCloseReason(lead.getMetadata(), cmd.closeReason()));
        }

        return leadRepo.save(lead);
    }

    private String extractCloseReason(String metadataJson) {
        try {
            @SuppressWarnings("unchecked")
            var map = objectMapper.readValue(metadataJson, Map.class);
            var v = map.get("closeReason");
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String mergeCloseReason(String metadataJson, String closeReason) {
        try {
            @SuppressWarnings("unchecked")
            var parsed = objectMapper.readValue(metadataJson, Map.class);
            var map = new LinkedHashMap<String, Object>(parsed);
            map.put("closeReason", closeReason);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return metadataJson;
        }
    }
}
