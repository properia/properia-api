package pt.properia.api.modules.acquisition.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.acquisition.domain.PropertyValuationRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PropertyValuationRequestJpaRepository
        extends JpaRepository<PropertyValuationRequest, UUID> {

    Optional<PropertyValuationRequest> findByPublicToken(String publicToken);

    Optional<PropertyValuationRequest> findByLeadId(UUID leadId);

    /**
     * Antifraude/dedup: quantos pedidos vieram do mesmo email numa janela recente.
     * O join com `leads` é preciso porque o email vive no lead, não no snapshot.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT count(*) FROM properia.property_valuation_requests r
        JOIN properia.leads l ON l.id = r.lead_id
        WHERE lower(l.contact_email) = lower(:email)
          AND r.created_at >= :since
        """, nativeQuery = true)
    long countRecentByContactEmail(String email, Instant since);
}
