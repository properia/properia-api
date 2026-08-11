package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deteta, a partir do texto do anúncio, imóveis cujo preço não corresponde à compra
 * plena do bem: nua propriedade/usufruto, quota parte, e exploração turística.
 *
 * Porquê regras determinísticas e não uma chamada à IA: isto decide se um imóvel é
 * escondido da pesquisa por omissão e penalizado no ranking. Um falso positivo
 * esconde inventário legítimo, um falso negativo deixa passar um preço enganoso —
 * ambos com consequência comercial direta. Regras explícitas são auditáveis,
 * testáveis, sem custo por chamada e sem variação entre execuções; um LLM aqui
 * traria não-determinismo a uma decisão que tem de ser sempre igual para o mesmo
 * texto. As regras cobrem também as variantes sem acento (anúncios são escritos em
 * teclados e fontes muito diferentes) via normalização prévia.
 */
@Component
public class SpecialConditionClassifier {

    // ── Domínio (espelha o CHECK da V76) ────────────────────────────────────────
    public static final String OWNERSHIP_FULL = "FULL";
    public static final String OWNERSHIP_NUDE = "NUDE_OWNERSHIP";
    public static final String OWNERSHIP_PARTIAL = "PARTIAL_SHARE";

    public static final String RESTRICTION_NONE = "NONE";
    public static final String RESTRICTION_TENANT = "TENANT_VITALICIO";
    public static final String RESTRICTION_TOURISTIC = "TOURISTIC_EXPLORATION";

    public record Classification(String ownershipType, String usageRestriction, String summary) {
        public boolean isSpecial() {
            return !OWNERSHIP_FULL.equals(ownershipType) || !RESTRICTION_NONE.equals(usageRestriction);
        }
        public static Classification none() {
            return new Classification(OWNERSHIP_FULL, RESTRICTION_NONE, null);
        }
    }

    // Padrões aplicados sobre texto já normalizado (minúsculas, sem acentos).
    private static final List<Pattern> NUDE_OWNERSHIP = List.of(
        Pattern.compile("\\bnua[- ]propriedade\\b"),
        Pattern.compile("\\busufrut"),                       // usufruto, usufrutuária, usufrutuário
        Pattern.compile("\\bresidente vitalicio\\b"),
        Pattern.compile("\\bdireito de habitacao vitalici")
    );

    private static final List<Pattern> PARTIAL_SHARE = List.of(
        Pattern.compile("\\bquota[- ]parte\\b"),
        Pattern.compile("\\bcompropriedade\\b"),
        Pattern.compile("\\bcomproprietari"),
        // "1/2 do imóvel", "50% da propriedade" — o número só conta quando ligado
        // ao bem, senão "50% financiado" ou "1/2 hora da praia" davam falso positivo.
        Pattern.compile("\\b\\d{1,3}\\s*%\\s+(da|do)\\s+(propriedade|imovel|fracao|predio|moradia|apartamento)\\b"),
        Pattern.compile("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\s+(da|do)\\s+(propriedade|imovel|fracao|predio|moradia|apartamento)\\b"),
        Pattern.compile("\\bvenda de \\d{1,3}\\s*%")
    );

    private static final List<Pattern> TOURISTIC = List.of(
        Pattern.compile("\\bexploracao turistica\\b"),
        Pattern.compile("\\bexploracao hoteleira\\b"),
        Pattern.compile("\\bpool hoteleiro\\b"),
        Pattern.compile("\\baparthotel\\b"),
        Pattern.compile("\\brendimento garantido\\b"),
        // "utilização limitada a 14 dias por ano", "direitos de uso 30 dias"
        Pattern.compile("\\b(utilizacao|uso)\\b[^.]{0,40}\\b\\d{1,3}\\s*dias\\b"),
        Pattern.compile("\\bdireitos de uso\\b")
    );

    private static final List<Pattern> TENANT_VITALICIO = List.of(
        Pattern.compile("\\barrendamento vitalicio\\b"),
        Pattern.compile("\\binquilino vitalicio\\b"),
        Pattern.compile("\\bresidente vitalicio\\b"),
        Pattern.compile("\\bdireito de habitacao vitalici"),
        Pattern.compile("\\busufrutuari")                     // quem detém usufruto continua a habitar
    );

    // Resumos fixos por condição: o utilizador precisa de perceber exatamente a
    // mesma coisa sempre que vê a mesma condição — texto gerado variaria entre
    // anúncios e diluiria o aviso.
    private static final String SUMMARY_NUDE =
        "Venda exclusiva do direito de propriedade. O morador atual mantém direito de habitação vitalício.";
    private static final String SUMMARY_PARTIAL =
        "Aquisição referente apenas a uma fração/quota parte da propriedade total.";
    private static final String SUMMARY_TOURISTIC =
        "Imóvel sob contrato de exploração hoteleira com limite anual de utilização pelo proprietário.";

    /**
     * @param texts título, descrição e notas — analisados em conjunto porque a condição
     *              tanto aparece no título ("Venda de Nua Propriedade...") como só no
     *              corpo da descrição.
     */
    public Classification classify(String... texts) {
        var haystack = normalize(String.join(" \n ", java.util.Arrays.stream(texts)
            .filter(java.util.Objects::nonNull)
            .toList()));
        if (haystack.isBlank()) return Classification.none();

        boolean nude = matchesAny(haystack, NUDE_OWNERSHIP);
        boolean partial = matchesAny(haystack, PARTIAL_SHARE);
        boolean touristic = matchesAny(haystack, TOURISTIC);
        boolean tenant = matchesAny(haystack, TENANT_VITALICIO);

        // Nua propriedade manda sobre quota parte: um anúncio que fale das duas
        // ("50% da nua propriedade") é sobretudo uma venda sem uso imediato, que é
        // a restrição mais forte para quem quer habitar.
        var ownership = nude ? OWNERSHIP_NUDE : partial ? OWNERSHIP_PARTIAL : OWNERSHIP_FULL;

        // Exploração turística é uma restrição de USO e coexiste com propriedade
        // plena; inquilino vitalício acompanha tipicamente a nua propriedade.
        var restriction = touristic ? RESTRICTION_TOURISTIC : tenant ? RESTRICTION_TENANT : RESTRICTION_NONE;

        if (OWNERSHIP_FULL.equals(ownership) && RESTRICTION_NONE.equals(restriction)) {
            return Classification.none();
        }

        // Resumo pela condição mais limitativa para quem compra para habitar.
        String summary;
        if (OWNERSHIP_NUDE.equals(ownership)) summary = SUMMARY_NUDE;
        else if (OWNERSHIP_PARTIAL.equals(ownership)) summary = SUMMARY_PARTIAL;
        else summary = SUMMARY_TOURISTIC;

        return new Classification(ownership, restriction, summary);
    }

    private static boolean matchesAny(String haystack, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(haystack).find());
    }

    /** Minúsculas e sem acentos — "nua propriedade" e "NUA PROPRIEDADE" têm de bater igual. */
    private static String normalize(String raw) {
        if (raw == null) return "";
        var lowered = raw.toLowerCase(java.util.Locale.ROOT);
        var decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
