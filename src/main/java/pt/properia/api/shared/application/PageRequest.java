package pt.properia.api.shared.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Standard pagination parameters used across all list use cases.
 */
public record PageRequest(
    @Min(0) int page,
    @Min(1) @Max(100) int size
) {
    public static final PageRequest DEFAULT = new PageRequest(0, 20);

    public static PageRequest of(int page, int size) {
        return new PageRequest(Math.max(0, page), Math.min(100, Math.max(1, size)));
    }

    public int offset() {
        return page * size;
    }
}
