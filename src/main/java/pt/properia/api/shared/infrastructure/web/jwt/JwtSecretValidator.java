package pt.properia.api.shared.infrastructure.web.jwt;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * [SEGURANÇA] Falha o arranque se o segredo de assinatura do JWT (AUTH_SESSION_SECRET →
 * properia.jwt.secret) estiver em falta, for demasiado curto ou for o valor default público.
 *
 * Se o segredo for fraco, qualquer pessoa consegue forjar JWTs válidos e personificar
 * qualquer utilizador. Em produção isto tem de impedir o arranque; em dev/test apenas avisa.
 */
@Component
public class JwtSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretValidator.class);

    // Valor default público que consta em application.yml — nunca pode chegar a produção.
    private static final String DEFAULT_SECRET = "replace_me_with_a_long_random_secret_at_least_32_chars";
    private static final int MIN_LENGTH = 32;

    private final JwtProperties props;
    private final Environment environment;

    public JwtSecretValidator(JwtProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String secret = props.getSecret();
        boolean weak = secret == null
            || secret.isBlank()
            || secret.trim().length() < MIN_LENGTH
            || DEFAULT_SECRET.equals(secret.trim());

        if (!weak) {
            return;
        }

        String msg = "Segredo do JWT (AUTH_SESSION_SECRET / properia.jwt.secret) em falta, "
            + "com menos de " + MIN_LENGTH + " caracteres, ou igual ao valor default público. "
            + "Define um segredo aleatório e forte.";

        if (isDevOrTest()) {
            log.warn("[SEGURANÇA] {} (tolerado apenas em dev/test)", msg);
        } else {
            throw new IllegalStateException("[SEGURANÇA] " + msg + " Arranque abortado.");
        }
    }

    private boolean isDevOrTest() {
        String[] active = environment.getActiveProfiles();
        // Sem profile ativo → tratar como produção (fail-safe).
        if (active.length == 0) {
            return false;
        }
        for (String p : active) {
            if (p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("test") || p.equalsIgnoreCase("local")) {
                return true;
            }
        }
        return false;
    }
}
