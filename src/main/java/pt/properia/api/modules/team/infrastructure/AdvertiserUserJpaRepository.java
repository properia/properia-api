package pt.properia.api.modules.team.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pt.properia.api.modules.team.domain.AdvertiserUser;
import pt.properia.api.modules.team.domain.AdvertiserUserId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdvertiserUserJpaRepository extends JpaRepository<AdvertiserUser, AdvertiserUserId> {

    List<AdvertiserUser> findByAdvertiserIdOrderByCreatedAtAsc(UUID advertiserId);

    Optional<AdvertiserUser> findByAdvertiserIdAndUserId(UUID advertiserId, UUID userId);

    long countByAdvertiserId(UUID advertiserId);
}
