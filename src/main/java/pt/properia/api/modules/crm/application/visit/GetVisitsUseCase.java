package pt.properia.api.modules.crm.application.visit;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.crm.application.dto.VisitDto;
import pt.properia.api.modules.crm.domain.Visit;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;

import java.util.List;
import java.util.UUID;

@Service
public class GetVisitsUseCase {

    private final VisitJpaRepository visitRepo;

    public GetVisitsUseCase(VisitJpaRepository visitRepo) {
        this.visitRepo = visitRepo;
    }

    public List<VisitDto> forAdvertiser(UUID advertiserId) {
        return visitRepo.findByAdvertiserIdOrderByStartsAtDesc(advertiserId)
            .stream().map(this::toDto).toList();
    }

    public List<VisitDto> forBuyer(UUID buyerUserId) {
        return visitRepo.findByBuyerUserIdOrderByStartsAtDesc(buyerUserId)
            .stream().map(this::toDto).toList();
    }

    public List<VisitDto> forListingAndBuyer(UUID listingId, UUID buyerUserId) {
        return visitRepo.findByListingIdAndBuyerUserId(listingId, buyerUserId)
            .stream().map(this::toDto).toList();
    }

    private VisitDto toDto(Visit v) {
        return new VisitDto(
            v.getId(), v.getLeadId(), v.getListingId(), v.getAdvertiserId(), v.getBuyerUserId(),
            v.getMode(), v.getStatus(), v.getStartsAt(), v.getEndsAt(),
            v.getMeetingUrl(), v.getNotes(), v.getCreatedAt(), v.getUpdatedAt()
        );
    }
}
