package pt.properia.api.modules.locations.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.locations.application.LocationSuggestionDto;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Repository
public class JdbcLocationRepository {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final int MAX_RESULTS = 8;

    private final JdbcClient jdbc;

    public JdbcLocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LocationSuggestionDto> suggest(String query, boolean emptyState) {
        if (query == null || query.isBlank()) {
            return emptyState ? topDistricts() : List.of();
        }

        var normalized = normalize(query.strip());
        var pattern = "%" + normalized + "%";
        var results = new ArrayList<LocationSuggestionDto>();

        // Districts
        results.addAll(jdbc.sql("""
                SELECT DISTINCT district FROM properia.listing_location
                WHERE district IS NOT NULL
                  AND unaccent(lower(district)) LIKE unaccent(lower(:pattern))
                ORDER BY district
                LIMIT 3
                """)
            .param("pattern", pattern)
            .query((rs, n) -> {
                var d = rs.getString("district");
                return new LocationSuggestionDto(
                    "distrito:" + d, d, d, "distrito", "Distrito", normalize(d)
                );
            }).list());

        // Municipalities / cities
        results.addAll(jdbc.sql("""
                SELECT DISTINCT city, district FROM properia.listing_location
                WHERE city IS NOT NULL
                  AND unaccent(lower(city)) LIKE unaccent(lower(:pattern))
                ORDER BY city
                LIMIT 4
                """)
            .param("pattern", pattern)
            .query((rs, n) -> {
                var city = rs.getString("city");
                var district = rs.getString("district");
                return new LocationSuggestionDto(
                    "concelho:" + city, city, city, "concelho",
                    district != null ? district : null, normalize(city)
                );
            }).list());

        // Parishes
        results.addAll(jdbc.sql("""
                SELECT DISTINCT parish, city FROM properia.listing_location
                WHERE parish IS NOT NULL
                  AND unaccent(lower(parish)) LIKE unaccent(lower(:pattern))
                ORDER BY parish
                LIMIT 3
                """)
            .param("pattern", pattern)
            .query((rs, n) -> {
                var parish = rs.getString("parish");
                var city = rs.getString("city");
                return new LocationSuggestionDto(
                    "freguesia:" + parish, parish, parish, "freguesia",
                    city != null ? city : null, normalize(parish)
                );
            }).list());

        // Postal codes (only if query looks like a postal code)
        if (normalized.matches("\\d{2,}.*")) {
            results.addAll(jdbc.sql("""
                    SELECT DISTINCT postal_code, city FROM properia.listing_location
                    WHERE postal_code IS NOT NULL
                      AND postal_code LIKE :postalPattern
                    ORDER BY postal_code
                    LIMIT 3
                    """)
                .param("postalPattern", normalized.replaceAll("[^0-9]", "") + "%")
                .query((rs, n) -> {
                    var pc = rs.getString("postal_code");
                    var city = rs.getString("city");
                    return new LocationSuggestionDto(
                        "codigo_postal:" + pc, pc, pc, "codigo_postal",
                        city != null ? city : null, pc
                    );
                }).list());
        }

        // Deduplicate by id, cap at MAX_RESULTS
        var seen = new java.util.LinkedHashSet<String>();
        return results.stream()
            .filter(s -> seen.add(s.id()))
            .limit(MAX_RESULTS)
            .toList();
    }

    private List<LocationSuggestionDto> topDistricts() {
        return jdbc.sql("""
                SELECT DISTINCT district FROM properia.listing_location
                WHERE district IS NOT NULL
                ORDER BY district
                LIMIT 8
                """)
            .query((rs, n) -> {
                var d = rs.getString("district");
                return new LocationSuggestionDto(
                    "distrito:" + d, d, d, "distrito", "Distrito", normalize(d)
                );
            }).list();
    }

    private String normalize(String value) {
        if (value == null) return "";
        var decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase().strip();
    }
}
