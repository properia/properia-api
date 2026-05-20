package pt.properia.api.modules.chat.application.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageDto(
    UUID id,
    UUID conversationId,
    String senderType,
    UUID senderUserId,
    String messageType,
    String body,
    Instant createdAt
) {}
