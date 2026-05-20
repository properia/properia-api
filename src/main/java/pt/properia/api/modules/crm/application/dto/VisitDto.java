package pt.properia.api.modules.crm.application.dto;

import java.time.Instant;
import java.util.UUID;

public record VisitDto(
    UUID id,
    UUID leadId,
    UUID listingId,
    UUID advertiserId,
    UUID buyerUserId,
    String mode,
    String status,
    Instant startsAt,
    Instant endsAt,
    String meetingUrl,
    String notes,
    Instant createdAt,
    Instant updatedAt
) {}
