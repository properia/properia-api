package pt.properia.api.modules.locations.application;

public record LocationSuggestionDto(
    String id,
    String label,
    String value,
    String type,      // "distrito" | "concelho" | "freguesia" | "codigo_postal"
    String subtitle,
    String searchText
) {}
