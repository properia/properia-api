package pt.properia.api.modules.acquisition.interfaces.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Submissão do formulário público de angariação.
 *
 * Campos anti-bot ({@code hp}, {@code formStartedAt}) são a primeira linha de
 * defesa, antes do rate limit: são gratuitos e apanham a esmagadora maioria dos
 * bots simples sem penalizar utilizadores reais nem exigir CAPTCHA.
 */
public record CreateValuationRequestRequest(

    // ── Imóvel ────────────────────────────────────────────────────────────────
    @Size(max = 300) String addressRaw,
    @Size(max = 20) @Pattern(regexp = "^$|^\\d{4}-\\d{3}$",
        message = "Código postal inválido (formato 0000-000).") String postalCode,
    @Size(max = 120) String district,
    @NotBlank @Size(max = 120) String municipality,
    @Size(max = 120) String parish,
    @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
    @NotBlank @Size(max = 40) String propertyType,
    @Min(0) @Max(20) Integer bedrooms,
    @NotNull @DecimalMin("1.0") @DecimalMax("100000.0") BigDecimal usableAreaM2,
    @Size(max = 40) String conditionStatus,
    @Min(-5) @Max(200) Integer floorNumber,
    Boolean hasElevator,
    @Size(max = 10) String energyRating,

    // ── Qualificação ──────────────────────────────────────────────────────────
    @Pattern(regexp = "^$|^(immediate|3m|6m|exploring)$",
        message = "Horizonte de venda inválido.") String sellingHorizon,
    Boolean hasAgency,
    @Size(max = 2000) String motivation,

    // ── Contacto ──────────────────────────────────────────────────────────────
    @NotBlank @Size(min = 2, max = 120) String contactName,
    @NotBlank @Email @Size(max = 320) String contactEmail,
    @NotBlank @Size(min = 9, max = 40)
    @Pattern(regexp = "^[+0-9()\\-\\s/]+$", message = "Telefone inválido.") String contactPhone,

    // ── RGPD ──────────────────────────────────────────────────────────────────
    // consentGranted tem de ser explicitamente true: consentimento por omissão
    // não é consentimento.
    @NotNull Boolean consentGranted,
    @NotBlank @Size(min = 10, max = 2000) String consentText,
    Boolean marketingConsent,

    // ── Anti-bot ──────────────────────────────────────────────────────────────
    /** Honeypot: campo escondido por CSS. Um humano nunca o preenche. */
    String hp,
    /** Epoch em ms do momento em que o formulário abriu. */
    Long formStartedAt,

    // ── Proveniência ──────────────────────────────────────────────────────────
    Map<String, Object> utm
) {

    @AssertTrue(message = "É necessário autorizar o tratamento dos dados para continuar.")
    public boolean isConsentAccepted() {
        return Boolean.TRUE.equals(consentGranted);
    }

    @AssertTrue(message = "Pedido inválido.")
    public boolean isHoneypotEmpty() {
        return hp == null || hp.isBlank();
    }
}
