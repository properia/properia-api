package pt.properia.api.modules.crm.interfaces.request;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record RequestVisitRequest(
    @NotNull UUID listingId,
    UUID leadId,
    String mode,
    @NotNull Instant startsAt,
    Instant endsAt,
    String notes
) {}
