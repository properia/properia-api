package pt.properia.api.modules.crm.domain;

import jakarta.persistence.AttributeConverter;

/**
 * Lado do mercado a que o lead pertence.
 *
 * BUYER — procura: alguém interessado num imóvel que já existe no catálogo.
 * OWNER — angariação: um proprietário que quer vender. Não tem imóvel associado
 *         enquanto não for qualificado, porque o imóvel é precisamente o que se
 *         está a angariar.
 *
 * Os nomes Java são maiúsculos por convenção; o enum Postgres
 * (properia.lead_type) usa minúsculas. O conversor faz a ponte.
 */
public enum LeadType {

    BUYER("buyer"),
    OWNER("owner");

    private final String dbValue;

    LeadType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static LeadType fromDbValue(String value) {
        if (value == null) return BUYER;
        for (var type : values()) {
            if (type.dbValue.equalsIgnoreCase(value)) return type;
        }
        throw new IllegalArgumentException("lead_type desconhecido: " + value);
    }

    @jakarta.persistence.Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<LeadType, String> {
        @Override
        public String convertToDatabaseColumn(LeadType attribute) {
            return attribute == null ? BUYER.dbValue : attribute.dbValue;
        }

        @Override
        public LeadType convertToEntityAttribute(String dbData) {
            return fromDbValue(dbData);
        }
    }
}
