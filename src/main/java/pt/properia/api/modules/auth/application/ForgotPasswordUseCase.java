package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.infrastructure.ActionTokenService;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;

import java.time.Instant;

@Service
public class ForgotPasswordUseCase {

    private final AuthRepository repository;
    private final ActionTokenService tokenService;
    private final AuthEmailService emailService;

    public ForgotPasswordUseCase(AuthRepository repository,
                                  ActionTokenService tokenService,
                                  AuthEmailService emailService) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.emailService = emailService;
    }

    public record Command(String email) {}

    public void execute(Command command) {
        String normalizedEmail = command.email().toLowerCase().strip();
        var user = repository.findUserByEmail(normalizedEmail);

        // Always return success to prevent email enumeration
        if (user.isEmpty()) return;

        String opaqueToken = tokenService.generate();
        String tokenHash = tokenService.hash(opaqueToken);

        repository.invalidateActionTokens(user.get().id(), "password_reset");
        repository.createActionToken(new AuthRepository.CreateActionTokenInput(
            user.get().id(),
            normalizedEmail,
            "password_reset",
            tokenHash,
            Instant.now().plusSeconds(3600)
        ));

        emailService.sendPasswordReset(normalizedEmail, opaqueToken);
    }
}
