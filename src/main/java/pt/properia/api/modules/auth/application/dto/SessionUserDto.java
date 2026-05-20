package pt.properia.api.modules.auth.application.dto;

import java.util.UUID;

public record SessionUserDto(
    UUID sub,
    String email,
    String name,
    String role,
    String avatarUrl,
    boolean hasAdvertiserAccess,
    UUID activeAdvertiserId
) {}
