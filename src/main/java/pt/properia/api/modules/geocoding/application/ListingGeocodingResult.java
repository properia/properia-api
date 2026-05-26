package pt.properia.api.modules.geocoding.application;

public record ListingGeocodingResult(
    double latitude,
    double longitude,
    String precision,
    Double confidence,
    String displayAddress,
    String district,
    String city,
    String parish,
    String neighborhood,
    String street,
    String postalCode
) {}
