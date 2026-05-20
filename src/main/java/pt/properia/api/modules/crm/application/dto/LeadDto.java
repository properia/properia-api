package pt.properia.api.modules.crm.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LeadDto(
    UUID id,
    UUID listingId,
    UUID userId,
    UUID advertiserId,
    String source,
    String stage,
    String intentType,
    String message,
    String contactName,
    String contactEmail,
    String contactPhone,
    BigDecimal score,
    UUID assignedTo,
    Instant createdAt,
    Instant updatedAt
) {}
