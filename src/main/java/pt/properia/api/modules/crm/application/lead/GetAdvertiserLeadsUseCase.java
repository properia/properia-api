package pt.properia.api.modules.crm.application.lead;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.application.dto.LeadDto;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;

import java.util.List;
import java.util.UUID;

@Service
public class GetAdvertiserLeadsUseCase {

    private final LeadJpaRepository leadRepo;

    public GetAdvertiserLeadsUseCase(LeadJpaRepository leadRepo) {
        this.leadRepo = leadRepo;
    }

    public List<LeadDto> execute(UUID advertiserId) {
        return leadRepo.findByAdvertiserIdOrderByCreatedAtDesc(advertiserId)
            .stream()
            .map(l -> new LeadDto(
                l.getId(), l.getListingId(), l.getUserId(), l.getAdvertiserId(),
                l.getSource(), l.getStage(), l.getIntentType(),
                l.getMessage(), l.getContactName(), l.getContactEmail(), l.getContactPhone(),
                l.getScore(), l.getAssignedTo(), l.getCreatedAt(), l.getUpdatedAt()
            ))
            .toList();
    }
}
