package pt.properia.api.shared.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard error envelope returned by all API endpoints.
 * Matches the shape the Next.js frontend already expects:
 * { error: { code, message, details } }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    ErrorBody error
) {
    public record ErrorBody(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> details,
        Instant timestamp
    ) {}

    public record FieldError(
        String field,
        String message
    ) {}

    public static ApiError of(String code, String message) {
        return new ApiError(new ErrorBody(code, message, null, Instant.now()));
    }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(new ErrorBody(code, message, details, Instant.now()));
    }

    public static ApiError unauthorized() {
        return of("UNAUTHORIZED", "Sessão ausente ou expirada.");
    }

    public static ApiError forbidden() {
        return of("FORBIDDEN", "Acesso não autorizado.");
    }

    public static ApiError notFound(String resource) {
        return of("NOT_FOUND", resource + " não encontrado.");
    }

    public static ApiError internalError() {
        return of("INTERNAL_ERROR", "Erro interno. Tente novamente.");
    }
}
