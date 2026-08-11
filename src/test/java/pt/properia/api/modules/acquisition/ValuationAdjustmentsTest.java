package pt.properia.api.modules.acquisition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pt.properia.api.modules.acquisition.application.ValuationAdjustments;
import pt.properia.api.modules.acquisition.application.ValuationInput;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Teste unitário puro (sem Spring, sem base de dados, sem Docker) — corre sempre.
 *
 * O que está a ser protegido: estes fatores multiplicam o preço que é mostrado a
 * um proprietário e enviado por email. Um sinal trocado (valorizar o que devia
 * desvalorizar) produz uma estimativa plausível mas errada, que é o pior tipo de
 * erro possível aqui: ninguém repara até alguém reclamar.
 */
@DisplayName("Fatores de ajuste da estimativa")
class ValuationAdjustmentsTest {

    @Nested
    @DisplayName("Estado de conservação")
    class Condition {

        @Test
        void novoValorizaEParaRecuperarDesvaloriza() {
            assertThat(ValuationAdjustments.conditionFactor("new")).isGreaterThan(1.0);
            assertThat(ValuationAdjustments.conditionFactor("remodeled")).isGreaterThan(1.0);
            assertThat(ValuationAdjustments.conditionFactor("used_good")).isEqualTo(1.0);
            assertThat(ValuationAdjustments.conditionFactor("used_regular")).isLessThan(1.0);
            assertThat(ValuationAdjustments.conditionFactor("to_renovate")).isLessThan(0.90);
            assertThat(ValuationAdjustments.conditionFactor("shell_core")).isLessThan(0.80);
        }

        @Test
        @DisplayName("estado desconhecido ou ausente é neutro, nunca penalizador")
        void desconhecidoENeutro() {
            assertThat(ValuationAdjustments.conditionFactor(null)).isEqualTo(1.0);
            assertThat(ValuationAdjustments.conditionFactor("estado_inventado")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Andar e elevador")
    class Floor {

        @Test
        void resDoChaoDesvaloriza() {
            assertThat(ValuationAdjustments.floorFactor("apartment", 0, true)).isLessThan(1.0);
        }

        @Test
        @DisplayName("sem elevador, quanto mais alto pior — mas o desconto tem chão")
        void semElevadorDesvalorizaProgressivamente() {
            double a2 = ValuationAdjustments.floorFactor("apartment", 2, false);
            double a4 = ValuationAdjustments.floorFactor("apartment", 4, false);
            double a9 = ValuationAdjustments.floorFactor("apartment", 9, false);

            assertThat(a2).isLessThan(1.0);
            assertThat(a4).isLessThan(a2);
            assertThat(a9).isEqualTo(0.88);
        }

        @Test
        @DisplayName("com elevador, andar alto valoriza")
        void comElevadorAndarAltoValoriza() {
            assertThat(ValuationAdjustments.floorFactor("apartment", 6, true)).isGreaterThan(1.0);
            assertThat(ValuationAdjustments.floorFactor("apartment", 2, true)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("não se aplica a terrenos nem a comércio — '3.º andar' não significa nada num terreno")
        void naoSeAplicaANaoResidencial() {
            assertThat(ValuationAdjustments.floorFactor("land", 5, false)).isEqualTo(1.0);
            assertThat(ValuationAdjustments.floorFactor("warehouse", 0, false)).isEqualTo(1.0);
        }

        @Test
        void andarDesconhecidoENeutro() {
            assertThat(ValuationAdjustments.floorFactor("apartment", null, null)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Certificado energético")
    class Energy {

        @Test
        void escalaEMonotona() {
            double aPlus = ValuationAdjustments.energyFactor("A+");
            double a = ValuationAdjustments.energyFactor("A");
            double c = ValuationAdjustments.energyFactor("C");
            double g = ValuationAdjustments.energyFactor("G");

            assertThat(aPlus).isGreaterThan(a);
            assertThat(a).isGreaterThan(c);
            assertThat(c).isEqualTo(1.0);
            assertThat(c).isGreaterThan(g);
        }

        @Test
        @DisplayName("A+ não pode ser confundido com A por partilharem prefixo")
        void aPlusNaoColideComA() {
            assertThat(ValuationAdjustments.energyFactor("A+"))
                .isNotEqualTo(ValuationAdjustments.energyFactor("A"));
        }

        @Test
        void toleraMinusculasEEspacos() {
            assertThat(ValuationAdjustments.energyFactor("  b ")).isEqualTo(1.02);
        }

        @Test
        void semCertificadoENeutro() {
            assertThat(ValuationAdjustments.energyFactor(null)).isEqualTo(1.0);
            assertThat(ValuationAdjustments.energyFactor("")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Rácio área / tipologia")
    class AreaTypology {

        @Test
        @DisplayName("T2 com área típica não é ajustado")
        void areaTipicaNaoAjusta() {
            var factor = ValuationAdjustments.areaTypologyFactor(
                "apartment", 2, new BigDecimal("85"));
            assertThat(factor).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("muito maior do que o típico transaciona a €/m² mais baixo")
        void areaGrandeBaixaPrecoPorM2() {
            var factor = ValuationAdjustments.areaTypologyFactor(
                "apartment", 2, new BigDecimal("140"));
            assertThat(factor).isLessThan(1.0);
        }

        @Test
        @DisplayName("muito menor do que o típico transaciona a €/m² mais alto")
        void areaPequenaSobePrecoPorM2() {
            var factor = ValuationAdjustments.areaTypologyFactor(
                "apartment", 3, new BigDecimal("70"));
            assertThat(factor).isGreaterThan(1.0);
        }

        @Test
        @DisplayName("a correção é limitada — é uma tendência de mercado, não uma lei")
        void correcaoELimitada() {
            var absurdo = ValuationAdjustments.areaTypologyFactor(
                "apartment", 1, new BigDecimal("1000"));
            assertThat(absurdo).isGreaterThanOrEqualTo(0.93);
        }

        @Test
        @DisplayName("moradias variam demasiado para a heurística ser fiável")
        void naoSeAplicaAMoradias() {
            assertThat(ValuationAdjustments.areaTypologyFactor(
                "house", 2, new BigDecimal("400"))).isEqualTo(1.0);
        }

        @Test
        void areaEsperadaCresceComATipologia() {
            assertThat(ValuationAdjustments.expectedAreaFor(0))
                .isLessThan(ValuationAdjustments.expectedAreaFor(1));
            assertThat(ValuationAdjustments.expectedAreaFor(4))
                .isLessThan(ValuationAdjustments.expectedAreaFor(7));
        }
    }

    @Nested
    @DisplayName("Acumulação de fatores")
    class Total {

        @Test
        @DisplayName("o pior caso possível nunca desce abaixo do teto global")
        void piorCasoRespeitaTeto() {
            var input = new ValuationInput(
                "apartment", "sale", "Porto", "Porto", "Ramalde",
                1, new BigDecimal("300"),
                "shell_core", 8, false, "G");

            double total = ValuationAdjustments.totalFactor(input);
            assertThat(total).isGreaterThanOrEqualTo(ValuationAdjustments.MIN_TOTAL_FACTOR);
        }

        @Test
        @DisplayName("o melhor caso possível nunca sobe acima do teto global")
        void melhorCasoRespeitaTeto() {
            var input = new ValuationInput(
                "apartment", "sale", "Lisboa", "Lisboa", "Alvalade",
                3, new BigDecimal("40"),
                "new", 8, true, "A+");

            double total = ValuationAdjustments.totalFactor(input);
            assertThat(total).isLessThanOrEqualTo(ValuationAdjustments.MAX_TOTAL_FACTOR);
        }

        @Test
        @DisplayName("um imóvel sem características declaradas fica no preço da zona")
        void semCaracteristicasENeutro() {
            var input = new ValuationInput(
                "apartment", "sale", "Porto", "Porto", "Ramalde",
                null, new BigDecimal("100"),
                null, null, null, null);

            assertThat(ValuationAdjustments.totalFactor(input)).isCloseTo(1.0, within(0.0001));
        }
    }
}
