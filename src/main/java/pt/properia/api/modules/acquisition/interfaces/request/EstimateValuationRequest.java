package pt.properia.api.modules.acquisition.interfaces.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Pedido de estimativa. Sem qualquer dado pessoal — é propositado: este endpoint
 * é chamado a cada passo do formulário, antes de haver consentimento para tratar
 * dados do proprietário.
 */
public record EstimateValuationRequest(
    @NotBlank @Size(max = 40) String propertyType,
    @Size(max = 40) String businessType,
    @Size(max = 120) String district,
    @NotBlank @Size(max = 120) String municipality,
    @Size(max = 120) String parish,
    @Min(0) @Max(20) Integer bedrooms,
    @NotNull @DecimalMin("1.0") @DecimalMax("100000.0") BigDecimal usableAreaM2,
    @Size(max = 40) String conditionStatus,
    @Min(-5) @Max(200) Integer floorNumber,
    Boolean hasElevator,
    @Size(max = 10) String energyRating
) {}
