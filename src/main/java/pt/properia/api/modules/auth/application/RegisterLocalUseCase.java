package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.application.dto.AuthUserSummaryDto;
import pt.properia.api.modules.auth.infrastructure.PasswordService;
import pt.properia.api.shared.domain.DomainException;

@Service
public class RegisterLocalUseCase {

    private final AuthRepository repository;
    private final PasswordService passwordService;

    public RegisterLocalUseCase(AuthRepository repository, PasswordService passwordService) {
        this.repository = repository;
        this.passwordService = passwordService;
    }

    public record Command(String name, String email, String password, boolean marketingConsent) {}
    public record Result(AuthUserSummaryDto user, boolean requiresEmailVerification) {}

    public Result execute(Command command) {
        String normalizedEmail = command.email().toLowerCase().strip();

        if (repository.findUserByEmail(normalizedEmail).isPresent()) {
            throw new DomainException("CONFLICT", "Já existe uma conta com este email.", 409);
        }

        passwordService.assertPolicy(command.password());
        String hash = passwordService.hash(command.password());

        var user = repository.createLocalUser(new AuthRepository.CreateLocalUserInput(
            command.name().strip(),
            normalizedEmail,
            hash,
            command.marketingConsent()
        ));

        return new Result(user, true);
    }
}
