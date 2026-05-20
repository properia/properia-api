package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.infrastructure.ActionTokenService;
import pt.properia.api.modules.auth.infrastructure.PasswordService;
import pt.properia.api.shared.domain.DomainException;

@Service
public class ResetPasswordUseCase {

    private final AuthRepository repository;
    private final PasswordService passwordService;
    private final ActionTokenService tokenService;

    public ResetPasswordUseCase(AuthRepository repository,
                                 PasswordService passwordService,
                                 ActionTokenService tokenService) {
        this.repository = repository;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
    }

    public record Command(String token, String newPassword) {}

    public void execute(Command command) {
        passwordService.assertPolicy(command.newPassword());

        String tokenHash = tokenService.hash(command.token());
        var actionToken = repository.consumeActionToken(tokenHash, "password_reset")
            .orElseThrow(() -> new DomainException("INVALID_TOKEN",
                "O link para repor a palavra-passe é inválido ou expirou."));

        if (actionToken.getUserId() == null) {
            throw new DomainException("INVALID_TOKEN",
                "O link para repor a palavra-passe é inválido ou expirou.");
        }

        // Prevent reuse of the same password
        var identity = repository.findLocalIdentityByEmail(actionToken.getEmail());
        if (identity.isPresent() && identity.get().getPasswordHash() != null) {
            if (passwordService.verify(command.newPassword(), identity.get().getPasswordHash())) {
                throw new DomainException("VALIDATION_ERROR",
                    "Escolhe uma palavra-passe diferente da atual.");
            }
        }

        String newHash = passwordService.hash(command.newPassword());
        repository.updatePassword(actionToken.getUserId(), newHash, "scrypt");
        repository.invalidateActionTokens(actionToken.getUserId(), "password_reset");
    }
}
