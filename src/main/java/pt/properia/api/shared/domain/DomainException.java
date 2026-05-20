package pt.properia.api.shared.domain;

/**
 * Thrown when a domain invariant is violated.
 * Maps to HTTP 422 by default; custom status codes supported for auth flows.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public DomainException(String code, String message) {
        this(code, message, 422);
    }

    public DomainException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }

    public static DomainException unauthorized(String message) {
        return new DomainException("UNAUTHORIZED", message, 401);
    }

    public static DomainException conflict(String message) {
        return new DomainException("CONFLICT", message, 409);
    }

    public static DomainException notFound(String message) {
        return new DomainException("NOT_FOUND", message, 404);
    }
}
