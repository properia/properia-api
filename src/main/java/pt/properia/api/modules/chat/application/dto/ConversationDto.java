package pt.properia.api.modules.chat.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDto(
    UUID id,
    UUID advertiserId,
    UUID listingId,
    UUID leadId,
    UUID buyerUserId,
    String status,
    Instant lastMessageAt,
    String lastMessagePreview,
    Instant createdAt,
    List<MessageDto> messages
) {}
