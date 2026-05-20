package pt.properia.api.shared.domain.valueobjects;

import pt.properia.api.shared.domain.DomainException;

/**
 * Immutable geographic coordinates (WGS84).
 * Portugal bounding box is enforced for domain validation.
 */
public record Coordinates(double latitude, double longitude) {

    // Loose bounding box covering mainland Portugal + islands
    private static final double LAT_MIN = 30.0;
    private static final double LAT_MAX = 43.0;
    private static final double LON_MIN = -32.0;
    private static final double LON_MAX = -6.0;

    public Coordinates {
        if (latitude < -90 || latitude > 90) {
            throw new DomainException("INVALID_COORDINATES", "Latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new DomainException("INVALID_COORDINATES", "Longitude out of range: " + longitude);
        }
    }

    public boolean isWithinPortugal() {
        return latitude >= LAT_MIN && latitude <= LAT_MAX
            && longitude >= LON_MIN && longitude <= LON_MAX;
    }

    public double distanceKmTo(Coordinates other) {
        // Haversine formula
        final double R = 6371.0;
        double dLat = Math.toRadians(other.latitude - this.latitude);
        double dLon = Math.toRadians(other.longitude - this.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(this.latitude))
            * Math.cos(Math.toRadians(other.latitude))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
