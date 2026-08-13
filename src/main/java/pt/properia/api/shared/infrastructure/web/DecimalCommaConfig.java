package pt.properia.api.shared.infrastructure.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Aceita números decimais escritos à portuguesa ("64,2") nos corpos JSON.
 *
 * Porquê: as fichas de imóvel em Portugal trazem "64,2 m²", e é assim que o
 * anunciante escreve. O Jackson só aceita ponto, portanto a vírgula rebentava a
 * desserialização do pedido INTEIRO antes de qualquer validação — resposta 500,
 * sem indicação do campo em causa, e o anúncio nunca chegava a ser criado.
 * Aconteceu em produção ao carregar um T2 com 64,2 m² de área útil.
 *
 * O cliente já normaliza antes de enviar; isto é a rede de segurança para
 * integrações, importações e clientes antigos, onde não controlamos o formato.
 *
 * Deliberadamente conservador: converte apenas a vírgula decimal e separadores de
 * milhares inequívocos. O que não for reconhecível segue para o Jackson e falha
 * como antes — é preferível um erro a inventar um valor que ninguém escreveu.
 */
@Configuration
public class DecimalCommaConfig {

    @Bean
    public SimpleModule lenientDecimalModule() {
        var module = new SimpleModule("lenient-decimals");
        module.addDeserializer(BigDecimal.class, new LenientBigDecimalDeserializer());
        return module;
    }

    static class LenientBigDecimalDeserializer extends StdDeserializer<BigDecimal> {

        LenientBigDecimalDeserializer() {
            super(BigDecimal.class);
        }

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() != null && p.currentToken().isNumeric()) {
                return p.getDecimalValue();
            }

            var raw = p.getValueAsString();
            if (raw == null) return null;

            var text = raw.trim();
            if (text.isEmpty()) return null;

            return new BigDecimal(normalize(text));
        }

        /** "64,2" → "64.2"; "1.234,5" → "1234.5"; "1,234.5" → "1234.5". */
        static String normalize(String text) {
            var cleaned = text.replace(" ", "").replace(" ", "");
            int lastComma = cleaned.lastIndexOf(',');
            int lastDot = cleaned.lastIndexOf('.');

            if (lastComma >= 0 && lastDot >= 0) {
                // O separador decimal é o que estiver mais à direita.
                return lastComma > lastDot
                    ? cleaned.replace(".", "").replace(',', '.')
                    : cleaned.replace(",", "");
            }
            if (lastComma >= 0) {
                // Uma única vírgula é sempre decimal aqui: "1,234" como milhares é
                // ambíguo, e em PT significa 1,234 — tratá-la como milhares
                // multiplicaria o valor por mil.
                return cleaned.replace(',', '.');
            }
            return cleaned;
        }
    }
}
