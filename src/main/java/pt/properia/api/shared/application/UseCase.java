package pt.properia.api.shared.application;

/**
 * Generic use case interface enforcing the Command → Result pattern.
 * Each use case has exactly one public method: execute().
 * This enforces SRP — one class, one responsibility.
 *
 * @param <C> Command (input DTO)
 * @param <R> Result (output DTO)
 */
public interface UseCase<C, R> {
    R execute(C command);
}
