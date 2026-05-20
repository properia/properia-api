package pt.properia.api.shared.domain.valueobjects;

import pt.properia.api.shared.domain.DomainException;

import java.util.regex.Pattern;

/**
 * Immutable value object for validated email addresses.
 * Uses citext-compatible lowercasing (matches the PostgreSQL citext column).
 */
public record Email(String value) {

    private static final Pattern PATTERN =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new DomainException("INVALID_EMAIL", "Email cannot be blank");
        }
        value = value.strip().toLowerCase();
        if (!PATTERN.matcher(value).matches()) {
            throw new DomainException("INVALID_EMAIL", "Invalid email format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
