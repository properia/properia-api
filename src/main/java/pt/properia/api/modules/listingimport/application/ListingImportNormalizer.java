package pt.properia.api.modules.listingimport.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalização determinística de valores crus de CRMs/portais para os enums e
 * tipos do Properia. Faz o trabalho barato e previsível (sem IA), deixando a IA
 * apenas para o mapeamento coluna→campo. Cobre PT + EN + valores comuns dos
 * feeds (Kyero, Idealista, Imovirtual).
 */
@Component
public class ListingImportNormalizer {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d.,\\s]*");
    private static final Pattern TYPOLOGY = Pattern.compile("\\bt(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /** Remove acentos e baixa a caixa para comparar valores de forma robusta. */
    static String key(String value) {
        if (value == null) return "";
        var stripped = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return stripped.replaceAll("[\\s_-]+", " ").trim();
    }

    // ── business_type ──────────────────────────────────────────────────────────

    public String businessType(String raw) {
        var k = key(raw);
        if (k.isEmpty()) return null;
        if (k.contains("trespasse") || k.contains("transfer")) return "transfer";
        if (k.contains("feria") || k.contains("holiday") || k.contains("vacacional") || k.contains("temporada")) return "holiday_rent";
        if (k.contains("arrend") || k.contains("rent") || k.contains("alug") || k.contains("let")) return "rent";
        if (k.contains("vend") || k.contains("sale") || k.contains("sell") || k.contains("compra") || k.contains("venta")) return "sale";
        return null;
    }

    // ── property_type ──────────────────────────────────────────────────────────

    public String propertyType(String raw) {
        var k = key(raw);
        if (k.isEmpty()) return null;
        if (k.contains("apartament") || k.contains("apartment") || k.contains("flat") || k.contains("piso") || k.contains("andar")) return "apartment";
        if (k.contains("estudio") || k.contains("studio") || k.equals("t0")) return "studio";
        if (k.contains("penthouse") || k.contains("cobertura") || k.contains("atico")) return "penthouse";
        if (k.contains("duplex")) return "duplex";
        if (k.contains("loft")) return "loft";
        if (k.contains("banda") || k.contains("townhouse") || k.contains("terraced")) return "townhouse";
        if (k.contains("geminad") || k.contains("semi detached") || k.contains("semi-detached")) return "semi_detached_house";
        if (k.contains("vivenda") || k.contains("villa")) return "villa";
        if (k.contains("moradia") || k.contains("house") || k.contains("casa") || k.contains("detached")) return "house";
        if (k.contains("quarto") || k.equals("room")) return "room";
        if (k.contains("terreno") || k.contains("land") || k.contains("plot") || k.contains("lote")) return "land";
        if (k.contains("escritorio") || k.contains("office")) return "office";
        if (k.contains("loja") || k.equals("shop") || k.contains("retail")) return "shop";
        if (k.contains("armazem") || k.contains("warehouse")) return "warehouse";
        if (k.contains("industrial") || k.contains("industria")) return "industrial";
        if (k.contains("garagem") || k.contains("garage") || k.contains("parking")) return "garage";
        if (k.contains("quinta") || k.contains("herdade") || k.contains("farm") || k.contains("rustic")) return "farm";
        if (k.contains("hotel") || k.contains("hostel") || k.contains("guest")) return "hotel";
        if (k.contains("predio") || k.contains("building") || k.contains("edificio")) return "building";
        if (k.contains("comercial") || k.contains("commercial")) return "commercial";
        return null;
    }

    // ── condition_status ───────────────────────────────────────────────────────

    public String condition(String raw) {
        var k = key(raw);
        if (k.isEmpty()) return null;
        if (k.contains("novo") || k.equals("new") || k.contains("brand new")) return "new";
        if (k.contains("remodelad") || k.contains("renovad") || k.contains("remodel") || k.contains("refurbish")) return "remodeled";
        if (k.contains("construc") || k.contains("construction") || k.contains("planta") || k.contains("off plan")) return "under_construction";
        if (k.contains("recuper") || k.contains("renovar") || k.contains("renovate") || k.contains("obras") || k.contains("fixer")) return "to_renovate";
        if (k.contains("tosco") || k.contains("bruto") || k.contains("shell")) return "shell_core";
        if (k.contains("razoavel") || k.contains("regular") || k.contains("fair")) return "used_regular";
        if (k.contains("bom") || k.contains("good") || k.contains("usad") || k.contains("used")) return "used_good";
        return null;
    }

    // ── furnished_status ───────────────────────────────────────────────────────

    public String furnished(String raw) {
        var k = key(raw);
        if (k.isEmpty()) return null;
        if (k.contains("semi")) return "semi_furnished";
        if (k.contains("mobilad") || k.contains("furnished") || k.contains("amueblad") || k.equals("sim") || k.equals("yes")) return "furnished";
        if (k.contains("sem mobil") || k.contains("unfurnished") || k.contains("por mobilar") || k.equals("nao") || k.equals("no")) return "unfurnished";
        return null;
    }

    // ── energy rating ──────────────────────────────────────────────────────────

    public String energyRating(String raw) {
        var k = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (k.isEmpty()) return null;
        // Aceita A+, A, B, B-, C, D, E, F, e "isento"/"exempt".
        if (k.matches("A\\+|A|B-|B|C|D|E|F")) return k;
        if (key(raw).contains("isent") || key(raw).contains("exempt")) return null;
        return null;
    }

    // ── números ────────────────────────────────────────────────────────────────

    public BigDecimal decimal(String raw) {
        if (raw == null) return null;
        var m = NUMBER.matcher(raw);
        if (!m.find()) return null;
        var num = m.group().trim().replaceAll("\\s", "");
        // Heurística PT/EU: se tem vírgula e ponto, o último é o decimal.
        if (num.contains(",") && num.contains(".")) {
            if (num.lastIndexOf(',') > num.lastIndexOf('.')) num = num.replace(".", "").replace(",", ".");
            else num = num.replace(",", "");
        } else if (num.contains(",")) {
            // vírgula única: decimal se seguida de 1-2 dígitos, senão separador de milhares
            var idx = num.indexOf(',');
            num = (num.length() - idx - 1 <= 2) ? num.replace(",", ".") : num.replace(",", "");
        }
        try {
            return new BigDecimal(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer integer(String raw) {
        var d = decimal(raw);
        return d == null ? null : d.intValue();
    }

    /** Extrai o nº de quartos de uma tipologia "T2", "T3 Duplex", etc. */
    public Integer bedroomsFromTypology(String raw) {
        if (raw == null) return null;
        var m = TYPOLOGY.matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Normaliza código postal PT para o formato "0000-000" quando possível. */
    public String postalCode(String raw) {
        if (raw == null) return null;
        var digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 7) return digits.substring(0, 4) + "-" + digits.substring(4);
        return raw.trim().isEmpty() ? null : raw.trim();
    }

    public String text(String raw) {
        if (raw == null) return null;
        var t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    /** Junta valores mapeados numa descrição, evitando nulos. */
    public String firstNonBlank(Map<String, String> record, String... candidates) {
        for (var c : candidates) {
            var v = record.get(c);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
