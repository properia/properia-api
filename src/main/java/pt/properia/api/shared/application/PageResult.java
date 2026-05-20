package pt.properia.api.shared.application;

import java.util.List;

/**
 * Standard paginated response envelope.
 * Matches the pagination shape expected by the Next.js frontend.
 */
public record PageResult<T>(
    List<T> items,
    long total,
    int page,
    int size,
    int totalPages
) {
    public static <T> PageResult<T> of(List<T> items, long total, PageRequest request) {
        int totalPages = (int) Math.ceil((double) total / request.size());
        return new PageResult<>(items, total, request.page(), request.size(), totalPages);
    }

    public static <T> PageResult<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResult<>(
            page.getContent(),
            page.getTotalElements(),
            page.getNumber(),
            page.getSize(),
            page.getTotalPages()
        );
    }

    public boolean hasNext() {
        return page < totalPages - 1;
    }
}
