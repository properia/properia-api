package pt.properia.api.modules.crm.interfaces.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateLeadRequest(
    @NotNull UUID listingId,
    String source,
    String intentType,
    String message,
    String contactName,
    @Email String contactEmail,
    String contactPhone
) {}
