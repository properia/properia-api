package pt.properia.api.modules.listings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pt.properia.api.modules.listings.application.SpecialConditionClassifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitário puro (sem Spring, sem base de dados, sem Docker) — corre sempre,
 * ao contrário da suite de integração, bloqueada nesta máquina por incompatibilidade
 * Testcontainers/Docker Desktop.
 *
 * O que está a ser protegido: esta classificação decide se um imóvel é escondido da
 * pesquisa por omissão e penalizado no ranking. Um falso positivo esconde inventário
 * legítimo; um falso negativo deixa passar um preço enganoso. Os casos negativos
 * abaixo valem tanto como os positivos.
 */
@DisplayName("Classificador de condições especiais de aquisição")
class SpecialConditionClassifierTest {

    private final SpecialConditionClassifier classifier = new SpecialConditionClassifier();

    @Nested
    @DisplayName("Nua propriedade / usufruto")
    class NudeOwnership {
        @Test
        void detetaNuaPropriedadeEUsufrutuaria() {
            var r = classifier.classify("Moradia T4 em Cascais",
                "Oportunidade de investimento em Nua Propriedade com usufrutuária de 74 anos.", null);
            assertThat(r.ownershipType()).isEqualTo("NUDE_OWNERSHIP");
            assertThat(r.usageRestriction()).isEqualTo("TENANT_VITALICIO");
            assertThat(r.summary()).contains("direito de habitação vitalício");
            assertThat(r.isSpecial()).isTrue();
        }

        @Test
        @DisplayName("apanha o termo mesmo sem acentos e em maiúsculas")
        void semAcentosEMaiusculas() {
            var r = classifier.classify(null, "VENDA DE NUA PROPRIEDADE COM USUFRUTO VITALICIO", null);
            assertThat(r.ownershipType()).isEqualTo("NUDE_OWNERSHIP");
        }

        @Test
        @DisplayName("deteta quando a condição só aparece no título")
        void apenasNoTitulo() {
            var r = classifier.classify("T3 nas Avenidas Novas — Nua Propriedade",
                "Apartamento com muita luz natural.", null);
            assertThat(r.ownershipType()).isEqualTo("NUDE_OWNERSHIP");
        }
    }

    @Nested
    @DisplayName("Quota parte / compropriedade")
    class PartialShare {
        @Test
        void detetaPercentagemDaPropriedade() {
            var r = classifier.classify("T2 no Estoril",
                "Venda de 50% da quota parte de um fantástico T2. Imóvel em compropriedade.", null);
            assertThat(r.ownershipType()).isEqualTo("PARTIAL_SHARE");
            assertThat(r.summary()).contains("quota parte");
        }

        @Test
        void detetaFracaoNumerica() {
            var r = classifier.classify(null, "Vende-se 1/2 do imóvel, restante pertence a herdeiros.", null);
            assertThat(r.ownershipType()).isEqualTo("PARTIAL_SHARE");
        }

        @Test
        @DisplayName("nua propriedade manda sobre quota parte quando aparecem as duas")
        void nuaPropriedadePrevalece() {
            var r = classifier.classify(null, "Venda de 50% da nua propriedade deste apartamento.", null);
            assertThat(r.ownershipType()).isEqualTo("NUDE_OWNERSHIP");
        }
    }

    @Nested
    @DisplayName("Exploração turística")
    class Touristic {
        @Test
        void detetaResortComLimiteDeDias() {
            var r = classifier.classify("Studio em resort",
                "Resort de 4 estrelas com exploração turística ativa. Utilização do proprietário limitada a 14 dias por ano.", null);
            assertThat(r.usageRestriction()).isEqualTo("TOURISTIC_EXPLORATION");
            assertThat(r.ownershipType()).isEqualTo("FULL");   // a propriedade é plena; o USO é que está limitado
            assertThat(r.summary()).contains("exploração hoteleira");
        }

        @Test
        void detetaAparthotelComRendimentoGarantido() {
            var r = classifier.classify(null,
                "T1 em aparthotel com rendimento garantido de 5% ao ano. Cede-se a exploração hoteleira total.", null);
            assertThat(r.usageRestriction()).isEqualTo("TOURISTIC_EXPLORATION");
            assertThat(r.isSpecial()).isTrue();
        }
    }

    @Nested
    @DisplayName("Anúncios normais — não podem ser marcados (falsos positivos)")
    class NoFalsePositives {
        @Test
        void anuncioComumFicaFull() {
            var r = classifier.classify("T2 em Cedofeita, Porto",
                "Apartamento T2 remodelado, cozinha equipada, boa exposição solar a sul.", null);
            assertThat(r.ownershipType()).isEqualTo("FULL");
            assertThat(r.usageRestriction()).isEqualTo("NONE");
            assertThat(r.summary()).isNull();
            assertThat(r.isSpecial()).isFalse();
        }

        @Test
        @DisplayName("percentagem sem ligação ao imóvel não é quota parte")
        void percentagemDeFinanciamentoNaoConta() {
            var r = classifier.classify(null,
                "Possibilidade de financiamento até 90%. Rentabilidade estimada de 4% ao ano.", null);
            assertThat(r.ownershipType()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("'a 1/2 hora da praia' não é venda de fração")
        void fracaoDeTempoNaoConta() {
            var r = classifier.classify(null, "Moradia a 1/2 hora da praia e 10 minutos do centro.", null);
            assertThat(r.ownershipType()).isEqualTo("FULL");
        }

        @Test
        void textoVazioOuNuloFicaFull() {
            assertThat(classifier.classify(null, null, null).isSpecial()).isFalse();
            assertThat(classifier.classify("", "  ", null).isSpecial()).isFalse();
        }
    }
}
