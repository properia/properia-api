package pt.properia.api.modules.listings.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que está a ser protegido: o classificador reconhecia "inquilino vitalício" mas
 * não "inquilina vitalícia". Veio de uma ficha real — "Arrendado com contrato de
 * inquilina vitalícia (162,90 €/mês)" — e a consequência é séria: um imóvel que o
 * comprador não pode ocupar era anunciado como venda normal, sem selo nem aviso.
 */
@DisplayName("Condições especiais — concordância de género")
class SpecialConditionGenderTest {

    private final SpecialConditionClassifier classifier = new SpecialConditionClassifier();

    private String restricao(String texto) {
        return classifier.classify("Apartamento T2", texto, null).usageRestriction();
    }

    @Test
    @DisplayName("apanha a forma feminina, que era a que escapava")
    void formaFeminina() {
        assertThat(restricao("Arrendado com contrato de inquilina vitalícia, renda de 162,90 €/mês."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
        assertThat(restricao("Imóvel com inquilina vitalícia."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
        assertThat(restricao("Arrendamento vitalícia em vigor."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
    }

    @Test
    @DisplayName("continua a apanhar a forma masculina — sem regressão")
    void formaMasculina() {
        assertThat(restricao("Vendido com inquilino vitalício."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
        assertThat(restricao("Arrendamento vitalício em vigor."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
        assertThat(restricao("Direito de habitação vitalício da atual moradora."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
    }

    @Test
    @DisplayName("uma venda normal continua a ser venda normal")
    void vendaNormalNaoEMarcada() {
        assertThat(restricao("Apartamento renovado, entrega livre na escritura."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_NONE);
        // "vitalício" noutro contexto não deve disparar.
        assertThat(restricao("Garantia vitalícia nos equipamentos da cozinha."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_NONE);
    }

    @Test
    @DisplayName("arrendado + vitalício separados por palavras no meio")
    void arrendadoComPalavrasNoMeio() {
        assertThat(restricao("Encontra-se arrendado ao abrigo de um contrato vitalício."))
            .isEqualTo(SpecialConditionClassifier.RESTRICTION_TENANT);
    }
}
