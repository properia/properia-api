package pt.properia.api.shared.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for all domain events.
 * Spring's ApplicationEventPublisher dispatches these to @EventListener handlers.
 */
public interface DomainEvent {
    UUID eventId();
    Instant occurredAt();
}
