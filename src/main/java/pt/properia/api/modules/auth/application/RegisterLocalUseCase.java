package pt.properia.api.modules.auth.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.auth.application.dto.AuthUserSummaryDto;
import pt.properia.api.modules.auth.infrastructure.ActionTokenService;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.modules.auth.infrastructure.PasswordService;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;

@Service
public class RegisterLocalUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterLocalUseCase.class);

    private final AuthRepository repository;
    private final PasswordService passwordService;
    private final ActionTokenService tokenService;
    private final AuthEmailService emailService;

    public RegisterLocalUseCase(AuthRepository repository, PasswordService passwordService,
                                 ActionTokenService tokenService, AuthEmailService emailService) {
        this.repository = repository;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.emailService = emailService;
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

        // Best-effort: a conta já está criada nesta altura, por isso uma falha a enviar
        // o email de confirmação não deve reverter/bloquear o registo (o utilizador pode
        // sempre reenviar a partir do perfil).
        try {
            String opaqueToken = tokenService.generate();
            String tokenHash = tokenService.hash(opaqueToken);
            repository.createActionToken(new AuthRepository.CreateActionTokenInput(
                user.id(),
                normalizedEmail,
                "email_verification",
                tokenHash,
                Instant.now().plusSeconds(48 * 3600)
            ));
            emailService.sendEmailVerification(normalizedEmail, opaqueToken);
        } catch (Exception e) {
            log.warn("Não foi possível enviar o email de confirmação no registo para {}: {}", normalizedEmail, e.getMessage());
        }

        return new Result(user, true);
    }
}
