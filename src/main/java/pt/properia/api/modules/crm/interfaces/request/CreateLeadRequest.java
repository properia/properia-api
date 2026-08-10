package pt.properia.api.modules.crm.interfaces.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record CreateLeadRequest(
    @NotNull UUID listingId,
    String source,
    String intentType,
    String message,
    String contactName,
    @Email String contactEmail,
    String contactPhone,
    // Contexto de captação que não cabe no enum lead_source (ex.: sourceContext da
    // calculadora de custos estimados). Guardado tal-e-qual em leads.metadata.
    Map<String, Object> metadata
) {}
