package pt.properia.api.modules.buyers.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.buyers.domain.BuyerProfile;
import pt.properia.api.modules.buyers.infrastructure.BuyerProfileJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class BuyerService {

    private final BuyerProfileJpaRepository repo;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public BuyerService(BuyerProfileJpaRepository repo,
                        org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public record BuyerListResult(List<BuyerProfile> items, long total, int page, int pageSize, int totalPages) {}

    @Transactional(readOnly = true)
    public BuyerListResult listProfiles(UUID advertiserId, String status, UUID assignedToUserId,
                                        String q, int page, int pageSize) {
        var pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = repo.search(advertiserId, status, assignedToUserId, q, pageable);
        var items = result.getContent();
        applyMatchCounts(advertiserId, items);
        return new BuyerListResult(
            items, result.getTotalElements(), page, pageSize, result.getTotalPages()
        );
    }

    private void applyMatchCounts(UUID advertiserId, List<BuyerProfile> items) {
        if (items.isEmpty()) return;
        var counts = new java.util.HashMap<UUID, Integer>();
        jdbc.sql("SELECT buyer_profile_id, COUNT(*) AS c FROM properia.buyer_listing_matches WHERE advertiser_id = :adv GROUP BY buyer_profile_id")
            .param("adv", advertiserId)
            .query((rs, n) -> counts.put(rs.getObject("buyer_profile_id", UUID.class), rs.getInt("c")))
            .list();
        for (var item : items) {
            item.setMatchCount(counts.getOrDefault(item.getId(), 0));
        }
    }

    @Transactional(readOnly = true)
    public BuyerProfile getProfile(UUID advertiserId, UUID id, UUID assignedToUserId) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        return profile;
    }

    public BuyerProfile createProfile(UUID advertiserId, UUID assignedToUserId, Map<String, Object> input) {
        var profile = new BuyerProfile();
        profile.setAdvertiserId(advertiserId);
        profile.setAssignedToUserId(assignedToUserId);
        profile.setConsentToken(UUID.randomUUID());
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        applyInput(profile, input);
        return repo.save(profile);
    }

    public BuyerProfile updateProfile(UUID advertiserId, UUID id, UUID assignedToUserId, Map<String, Object> input) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        applyInput(profile, input);
        profile.setUpdatedAt(Instant.now());
        return repo.save(profile);
    }

    public void deleteProfile(UUID advertiserId, UUID id, UUID assignedToUserId) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        repo.delete(profile);
    }

    @SuppressWarnings("unchecked")
    private void applyInput(BuyerProfile p, Map<String, Object> input) {
        if (input.containsKey("name")) p.setName((String) input.get("name"));
        if (input.containsKey("email")) p.setEmail((String) input.get("email"));
        if (input.containsKey("phone")) p.setPhone((String) input.get("phone"));
        if (input.containsKey("urgency")) p.setUrgency((String) input.get("urgency"));
        if (input.containsKey("budgetBracket")) p.setBudgetBracket((String) input.get("budgetBracket"));
        if (input.containsKey("budgetApproval")) p.setBudgetApproval((String) input.get("budgetApproval"));
        if (input.containsKey("situation")) p.setSituation((String) input.get("situation"));
        if (input.containsKey("status")) p.setStatus((String) input.get("status"));
        if (input.containsKey("closeReason")) p.setCloseReason((String) input.get("closeReason"));
        if (input.containsKey("internalNotes")) p.setInternalNotes((String) input.get("internalNotes"));
        if (input.containsKey("criteria")) p.setCriteria((Map<String, Object>) input.get("criteria"));
        if (input.containsKey("assignedToUserId") && input.get("assignedToUserId") != null) {
            p.setAssignedToUserId(UUID.fromString((String) input.get("assignedToUserId")));
        }
        if (input.containsKey("nextFollowUpAt") && input.get("nextFollowUpAt") != null) {
            p.setNextFollowUpAt(Instant.parse((String) input.get("nextFollowUpAt")));
        }
    }
}
