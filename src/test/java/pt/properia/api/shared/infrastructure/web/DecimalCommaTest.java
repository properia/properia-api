package pt.properia.api.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pt.properia.api.shared.infrastructure.web.DecimalCommaConfig.LenientBigDecimalDeserializer.normalize;

/**
 * O que está a ser protegido: uma área escrita "64,2" — como vem em qualquer ficha
 * de imóvel portuguesa — rebentava a desserialização do pedido inteiro e devolvia
 * HTTP 500 sem dizer qual o campo. O anúncio nunca chegava a ser criado.
 */
@DisplayName("JSON — decimais escritos à portuguesa")
class DecimalCommaTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new DecimalCommaConfig().lenientDecimalModule());

    record Payload(BigDecimal usableAreaM2) {}

    @Test
    @DisplayName("aceita a vírgula decimal que partia o pedido")
    void aceitaVirgulaDecimal() throws Exception {
        var p = mapper.readValue("{\"usableAreaM2\":\"64,2\"}", Payload.class);
        assertThat(p.usableAreaM2()).isEqualByComparingTo("64.2");
    }

    @Test
    @DisplayName("não regride nos formatos que já funcionavam")
    void formatosAntigosIntactos() throws Exception {
        assertThat(mapper.readValue("{\"usableAreaM2\":\"64.2\"}", Payload.class).usableAreaM2())
            .isEqualByComparingTo("64.2");
        assertThat(mapper.readValue("{\"usableAreaM2\":64.2}", Payload.class).usableAreaM2())
            .isEqualByComparingTo("64.2");
        assertThat(mapper.readValue("{\"usableAreaM2\":\"64\"}", Payload.class).usableAreaM2())
            .isEqualByComparingTo("64");
    }

    @Test
    @DisplayName("vazio e nulo continuam a ser nulos, não zero")
    void vaziosNaoViramZero() throws Exception {
        assertThat(mapper.readValue("{\"usableAreaM2\":null}", Payload.class).usableAreaM2()).isNull();
        assertThat(mapper.readValue("{\"usableAreaM2\":\"\"}", Payload.class).usableAreaM2()).isNull();
        assertThat(mapper.readValue("{\"usableAreaM2\":\"  \"}", Payload.class).usableAreaM2()).isNull();
    }

    @Test
    @DisplayName("separadores de milhares não multiplicam o valor")
    void milharesNaoDistorcemOValor() {
        assertThat(normalize("1.234,5")).isEqualTo("1234.5");   // formato PT
        assertThat(normalize("1,234.5")).isEqualTo("1234.5");   // formato EN
        // Uma vírgula sozinha é decimal: em PT "1,234" é 1,234 e não 1234.
        assertThat(normalize("1,234")).isEqualTo("1.234");
    }

    @Test
    @DisplayName("lixo continua a falhar em vez de virar um número inventado")
    void lixoContinuaAFalhar() {
        assertThatThrownBy(() -> mapper.readValue("{\"usableAreaM2\":\"sessenta\"}", Payload.class))
            .isInstanceOf(Exception.class);
    }
}
