package pt.properia.api.modules.auth.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.infrastructure.ActionTokenService;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;

@Service
public class ResendVerificationUseCase {

    private final AuthRepository repository;
    private final ActionTokenService tokenService;
    private final AuthEmailService emailService;

    public ResendVerificationUseCase(AuthRepository repository,
                                      ActionTokenService tokenService,
                                      AuthEmailService emailService) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.emailService = emailService;
    }

    public record Command(String email) {}

    public void execute(Command command) {
        String normalizedEmail = command.email().toLowerCase().strip();
        var user = repository.findUserByEmail(normalizedEmail)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Conta não encontrada."));

        var identity = repository.findLocalIdentityByEmail(normalizedEmail)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Conta não encontrada."));

        if (identity.isEmailVerified()) {
            throw new DomainException("ALREADY_VERIFIED", "O email já foi verificado.");
        }

        String opaqueToken = tokenService.generate();
        String tokenHash = tokenService.hash(opaqueToken);

        repository.invalidateActionTokens(user.id(), "email_verification");
        repository.createActionToken(new AuthRepository.CreateActionTokenInput(
            user.id(),
            normalizedEmail,
            "email_verification",
            tokenHash,
            Instant.now().plusSeconds(48 * 3600)
        ));

        emailService.sendEmailVerification(normalizedEmail, opaqueToken);
    }
}
