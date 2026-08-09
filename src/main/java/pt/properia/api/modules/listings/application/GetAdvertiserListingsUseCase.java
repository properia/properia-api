package pt.properia.api.modules.listings.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.application.dto.ListingCardDto;

import java.util.List;
import java.util.UUID;

@Service
public class GetAdvertiserListingsUseCase {

    private final ListingRepository repository;
    private final JdbcClient jdbc;

    public GetAdvertiserListingsUseCase(ListingRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    public record Query(UUID advertiserId, UUID requestorUserId) {}

    public List<ListingCardDto> execute(Query query) {
        var listings = repository.findByAdvertiserId(query.advertiserId());
        if (!isScopedToSelf(query.advertiserId(), query.requestorUserId())) return listings;
        return listings.stream()
            .filter(l -> query.requestorUserId().equals(l.assignedAgentId()))
            .toList();
    }

    // Sales só vê os imóveis de que é responsável; owner/admin/editor veem todos.
    private boolean isScopedToSelf(UUID advertiserId, UUID userId) {
        var role = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query(String.class).optional().orElse(null);
        return "sales".equals(role);
    }
}
