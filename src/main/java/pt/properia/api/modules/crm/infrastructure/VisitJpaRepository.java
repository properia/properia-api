package pt.properia.api.modules.crm.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.properia.api.modules.crm.domain.Visit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitJpaRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByAdvertiserIdOrderByStartsAtDesc(UUID advertiserId);

    List<Visit> findByBuyerUserIdOrderByStartsAtDesc(UUID buyerUserId);

    Optional<Visit> findByIdAndAdvertiserId(UUID id, UUID advertiserId);

    Optional<Visit> findByIdAndBuyerUserId(UUID id, UUID buyerUserId);

    List<Visit> findByListingIdAndBuyerUserId(UUID listingId, UUID buyerUserId);

    // "status" é o enum nativo properia.visit_status — o derived-query "StatusIn" original
    // gerava `status in (?,?)` sem cast, e o Postgres rejeita comparar enum com varchar
    // (@ColumnTransformer não é aplicado dentro de um IN pelo Hibernate). Cast explícito
    // da coluna para string em JPQL evita a comparação ambígua sem precisar de bind de array.
    @Query("SELECT v FROM Visit v WHERE v.advertiserId = :advertiserId "
        + "AND CAST(v.status AS string) IN :statuses "
        + "AND v.startsAt BETWEEN :from AND :to")
    List<Visit> findByAdvertiserIdAndStatusInAndStartsAtBetween(
        @Param("advertiserId") UUID advertiserId,
        @Param("statuses") Collection<String> statuses,
        @Param("from") Instant from,
        @Param("to") Instant to);
}
