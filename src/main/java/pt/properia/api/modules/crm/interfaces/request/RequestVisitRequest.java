package pt.properia.api.modules.crm.interfaces.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RequestVisitRequest(
    @NotNull String listingId,
    String mode,
    @NotNull String slotStartsAt,
    String slotEndsAt,
    String selectedTimezone,
    @NotBlank String contactName,
    @NotBlank String contactEmail,
    String contactPhone,
    String message,
    Boolean operationalConsent,
    Boolean marketingConsent
) {}
