package pt.properia.api.modules.acquisition.interfaces.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyValuationContactRequest(
    @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Código inválido.") String code
) {}
