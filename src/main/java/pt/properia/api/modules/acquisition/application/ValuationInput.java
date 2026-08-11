package pt.properia.api.modules.acquisition.application;

import java.math.BigDecimal;

/**
 * Entradas do motor de estimativa. Tudo o que vem de um formulário público é
 * opcional exceto o tipo de imóvel, a localização e a área — sem esses três não
 * há cálculo possível.
 *
 * @param municipality concelho declarado. Nota: a tabela `listings` não tem
 *                     coluna `municipality`, guarda o concelho em `city`; a
 *                     correspondência é feita no repositório.
 */
public record ValuationInput(
    String propertyType,
    String businessType,
    String district,
    String municipality,
    String parish,
    Integer bedrooms,
    BigDecimal usableAreaM2,
    String conditionStatus,
    Integer floorNumber,
    Boolean hasElevator,
    String energyRating
) {

    /** Área máxima aceite; acima disto é quase de certeza erro de introdução. */
    private static final BigDecimal MAX_AREA_M2 = new BigDecimal("100000");

    public ValuationInput {
        businessType = (businessType == null || businessType.isBlank()) ? "sale" : businessType.trim();
        propertyType = propertyType == null ? null : propertyType.trim();
        district = blankToNull(district);
        municipality = blankToNull(municipality);
        parish = blankToNull(parish);
        conditionStatus = blankToNull(conditionStatus);
        energyRating = blankToNull(energyRating);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** Há dados suficientes para sequer tentar um cálculo? */
    public boolean isCalculable() {
        return propertyType != null && !propertyType.isBlank()
            && municipality != null
            && usableAreaM2 != null
            && usableAreaM2.signum() > 0
            && usableAreaM2.compareTo(MAX_AREA_M2) <= 0;
    }
}
