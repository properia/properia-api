package pt.properia.api.modules.auth.application.dto;

import java.util.UUID;

public record AuthUserSummaryDto(
    UUID id,
    String email,
    String name,
    String role,
    String avatarUrl
) {}
