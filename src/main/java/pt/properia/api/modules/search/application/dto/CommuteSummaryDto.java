package pt.properia.api.modules.search.application.dto;

public record CommuteSummaryDto(
    String destinationLabel,
    String mode,
    Integer durationMinutes,
    Double distanceKm,
    Integer maxMinutes,
    Boolean withinMaxMinutes
) {}
