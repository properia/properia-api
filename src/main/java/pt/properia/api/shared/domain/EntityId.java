package pt.properia.api.shared.domain;

import java.util.UUID;

/**
 * Base record for strongly-typed entity identifiers.
 * Prevents mixing up UUIDs from different aggregates at compile time.
 *
 * Usage: public record ListingId(UUID value) implements EntityId {}
 */
public interface EntityId {
    UUID value();

    static UUID generate() {
        return UUID.randomUUID();
    }
}
