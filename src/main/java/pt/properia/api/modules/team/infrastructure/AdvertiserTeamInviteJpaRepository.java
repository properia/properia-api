package pt.properia.api.modules.team.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import pt.properia.api.modules.team.domain.AdvertiserTeamInvite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdvertiserTeamInviteJpaRepository extends JpaRepository<AdvertiserTeamInvite, UUID> {

    List<AdvertiserTeamInvite> findByAdvertiserIdOrderByCreatedAtAsc(UUID advertiserId);

    Optional<AdvertiserTeamInvite> findByAdvertiserIdAndId(UUID advertiserId, UUID id);

    Optional<AdvertiserTeamInvite> findByToken(String token);

    @Modifying
    @Query("DELETE FROM AdvertiserTeamInvite i WHERE i.advertiserId = :advertiserId AND i.email = :email AND i.acceptedAt IS NULL")
    void deletePendingByAdvertiserAndEmail(UUID advertiserId, String email);
}
