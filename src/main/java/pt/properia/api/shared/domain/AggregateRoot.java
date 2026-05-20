package pt.properia.api.shared.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for all aggregate roots.
 * Aggregates accumulate domain events during their lifecycle.
 * Events are dispatched by the application layer after persistence.
 *
 * @param <ID> the strongly-typed identifier record for this aggregate
 */
public abstract class AggregateRoot<ID extends EntityId> {

    protected ID id;
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected AggregateRoot() {}

    protected AggregateRoot(ID id) {
        this.id = id;
    }

    public ID getId() {
        return id;
    }

    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    public List<DomainEvent> pullDomainEvents() {
        var events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }
}
