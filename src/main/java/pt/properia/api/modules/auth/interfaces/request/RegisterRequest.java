package pt.properia.api.modules.auth.interfaces.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 2, max = 100) String name,
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(min = 12, max = 128) String password,
    boolean marketingConsent,
    // Aceite dos Termos e Política de Privacidade é obrigatório e explícito. Sem isto,
    // o registo via API direta criava conta sem consentimento, mas o registo de
    // consentimentos gravava termsPrivacy.granted=true na mesma — um registo falso (RGPD).
    @AssertTrue(message = "É necessário aceitar os Termos e a Política de Privacidade.")
    boolean acceptTerms
) {}
