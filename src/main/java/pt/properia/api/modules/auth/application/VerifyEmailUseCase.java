package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.infrastructure.ActionTokenService;
import pt.properia.api.shared.domain.DomainException;

@Service
public class VerifyEmailUseCase {

    private final AuthRepository repository;
    private final ActionTokenService tokenService;

    public VerifyEmailUseCase(AuthRepository repository, ActionTokenService tokenService) {
        this.repository = repository;
        this.tokenService = tokenService;
    }

    public record Command(String token) {}

    public void execute(Command command) {
        String tokenHash = tokenService.hash(command.token());
        var actionToken = repository.consumeActionToken(tokenHash, "email_verification")
            .orElseThrow(() -> new DomainException("INVALID_TOKEN",
                "O link de verificação é inválido ou expirou."));

        if (actionToken.getUserId() == null) {
            throw new DomainException("INVALID_TOKEN", "O link de verificação é inválido ou expirou.");
        }

        repository.markEmailVerified(actionToken.getUserId(), actionToken.getEmail());
        repository.invalidateActionTokens(actionToken.getUserId(), "email_verification");
    }
}
