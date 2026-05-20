package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.application.dto.SessionUserDto;
import pt.properia.api.modules.auth.infrastructure.PasswordService;
import pt.properia.api.shared.domain.DomainException;

@Service
public class LoginLocalUseCase {

    private final AuthRepository repository;
    private final PasswordService passwordService;

    public LoginLocalUseCase(AuthRepository repository, PasswordService passwordService) {
        this.repository = repository;
        this.passwordService = passwordService;
    }

    public record Command(String email, String password) {}

    public SessionUserDto execute(Command command) {
        String normalizedEmail = command.email().toLowerCase().strip();

        var identity = repository.findLocalIdentityByEmail(normalizedEmail)
            .orElseThrow(() -> new DomainException("UNAUTHORIZED", "Credenciais inválidas.", 401));

        if (!passwordService.verify(command.password(), identity.getPasswordHash())) {
            throw new DomainException("UNAUTHORIZED", "Credenciais inválidas.", 401);
        }

        repository.touchIdentityLastLogin(identity.getId());
        return repository.buildSessionUser(identity.getUserId());
    }
}
