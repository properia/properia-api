package pt.properia.api.modules.geocoding.application;

public record GeocodingResult(
    String label,
    double lat,
    double lng
) {}
