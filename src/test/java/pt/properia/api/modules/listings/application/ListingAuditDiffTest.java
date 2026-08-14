package pt.properia.api.modules.listings.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O diff é o instrumento de medida — se ele mentir, a auditoria manda-nos atrás
 * do bug errado. Dois modos de falha importam:
 *
 * <ul>
 *   <li><b>Falsos positivos:</b> o mesmo número atravessa esta camada ora como
 *       BigDecimal, ora como String, ora como Integer. Um diff ingénuo marcaria
 *       cada PATCH com dezenas de "mudanças" que não mudaram nada, e o ruído
 *       esconderia o apagamento real.</li>
 *   <li><b>Falsos negativos:</b> deixar passar um campo que ficou vazio é
 *       exactamente perder aquilo que fomos lá buscar.</li>
 * </ul>
 */
class ListingAuditDiffTest {

    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Nested
    @DisplayName("ruído que não é mudança")
    class Noise {

        @Test
        @DisplayName("o mesmo número em tipos diferentes não conta como alteração")
        void numericRepresentationsAreEquivalent() {
            var before = map("priceAmount", new BigDecimal("185000.00"), "bedrooms", 2);
            var after = map("priceAmount", "185000", "bedrooms", "2");

            assertThat(ListingAuditService.diff(before, after, Set.of())).isEmpty();
        }

        @Test
        @DisplayName("null, string vazia e lista vazia são todos 'sem valor'")
        void emptyFormsAreEquivalent() {
            assertThat(ListingAuditService.diff(
                map("street", null, "featureTags", List.of()),
                map("street", "", "featureTags", List.of()),
                Set.of())).isEmpty();
        }

        @Test
        @DisplayName("um PATCH que não muda nada não escreve linha de auditoria")
        void identicalSnapshotsProduceNoChanges() {
            var snapshot = map("title", "T2 em Ermesinde", "priceAmount", "185000");
            assertThat(ListingAuditService.diff(snapshot, map("title", "T2 em Ermesinde",
                "priceAmount", "185000"), Set.of("title"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("apagamentos")
    class Wipes {

        @Test
        @DisplayName("distingue o campo que o cliente enviou do que desapareceu sozinho")
        void separatesIntentFromCollateralDamage() {
            // Cenário real: o wizard mandou só o preço, e a descrição gerada por IA
            // desapareceu na mesma. É esta a assinatura que procuramos.
            var before = map(
                "priceAmount", "185000",
                "descriptionRaw", "Apartamento T2 totalmente remodelado…",
                "street", "Rua de Santa Marta");
            var after = map(
                "priceAmount", "179000",
                "descriptionRaw", null,
                "street", "Rua de Santa Marta");

            var changes = ListingAuditService.diff(before, after, Set.of("priceAmount"));

            assertThat(changes).hasSize(2);

            var price = changes.stream().filter(c -> c.field().equals("priceAmount")).findFirst().orElseThrow();
            assertThat(price.sentByClient()).isTrue();

            var description = changes.stream().filter(c -> c.field().equals("descriptionRaw")).findFirst().orElseThrow();
            assertThat(description.sentByClient())
                .as("descriptionRaw ficou a null sem a chave vir no pedido — é dano colateral")
                .isFalse();
            assertThat(description.before()).isEqualTo("Apartamento T2 totalmente remodelado…");
            assertThat(description.after()).isNull();
        }

        @Test
        @DisplayName("preencher um campo vazio não é um apagamento")
        void fillingAnEmptyFieldIsNotAWipe() {
            var changes = ListingAuditService.diff(
                map("energyRating", null),
                map("energyRating", "B-"),
                Set.of("energyRating"));

            assertThat(changes).hasSize(1);
            assertThat(changes.getFirst().before()).isNull();
            assertThat(changes.getFirst().after()).isEqualTo("B-");
        }

        @Test
        @DisplayName("apanha a sub-entidade de localização reescrita por inteiro")
        void catchesSubEntityOverwrite() {
            // PatchListingService reescreve listing_location inteira quando QUALQUER
            // campo de morada chega. Mandar só a cidade limpa a rua e o código postal.
            var before = map("city", "Porto", "street", "Rua do Bonjardim", "postalCode", "4000-110");
            var after = map("city", "Gondomar", "street", null, "postalCode", null);

            var changes = ListingAuditService.diff(before, after, Set.of("city"));

            assertThat(changes.stream().filter(c -> !c.sentByClient()).map(ListingAuditService.FieldChange::field))
                .containsExactlyInAnyOrder("street", "postalCode");
        }
    }

    @Nested
    @DisplayName("campos derivados")
    class Derived {

        @Test
        @DisplayName("updatedAt não conta como alteração nem como campo não enviado")
        void serverManagedTimestampsAreIgnored() {
            var changes = ListingAuditService.diff(
                map("updatedAt", "2026-08-14T10:00:00Z", "publishedAt", null),
                map("updatedAt", "2026-08-14T10:05:00Z", "publishedAt", "2026-08-14T10:05:00Z"),
                Set.of("status"));

            assertThat(changes)
                .as("o servidor mexe nestes campos sempre; listá-los esvazia o significado de 'unsent'")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("robustez")
    class Robustness {

        @Test
        @DisplayName("uma fotografia em falta não inventa um diff")
        void missingSnapshotYieldsNothing() {
            assertThat(ListingAuditService.diff(null, map("title", "x"), Set.of())).isEmpty();
            assertThat(ListingAuditService.diff(map("title", "x"), null, Set.of())).isEmpty();
        }

        @Test
        @DisplayName("um campo que só existe no depois conta como alteração")
        void newFieldCounts() {
            var changes = ListingAuditService.diff(map(), map("virtualTourUrl", "https://x"), Set.of());
            assertThat(changes).singleElement()
                .extracting(ListingAuditService.FieldChange::field).isEqualTo("virtualTourUrl");
        }

        @Test
        @DisplayName("texto que se parece com número não é comparado como número")
        void nonNumericStringsFallBackToText() {
            var changes = ListingAuditService.diff(
                map("postalCode", "4000-110"), map("postalCode", "4000-111"), Set.of("postalCode"));
            assertThat(changes).hasSize(1);
        }
    }
}
