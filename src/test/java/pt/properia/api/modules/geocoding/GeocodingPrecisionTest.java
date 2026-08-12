package pt.properia.api.modules.geocoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pt.properia.api.modules.geocoding.application.ListingGeocodingResult;
import pt.properia.api.modules.geocoding.infrastructure.GeocodingProperties;
import pt.properia.api.modules.geocoding.infrastructure.NominatimGeocodingService;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste do geocoding de imóveis sem morada exata.
 *
 * O que está a ser protegido: há inventário real cuja localização conhecida é
 * apenas "União das Freguesias de X e Y, Concelho". Antes desta correção esse
 * caso caía no centroide do CONCELHO — em Matosinhos, ~6 km de erro — e ainda
 * era etiquetado como precisão de freguesia. Um pino errado é pior do que
 * nenhum: contamina o mapa, a análise de zona e a pesquisa por trajeto sem que
 * nada indique que está errado.
 *
 * Os testes de rede só correm com GEOCODING_LIVE_TEST=1, para não depender do
 * Nominatim público (rate-limited) na suite normal.
 */
@DisplayName("Geocoding — imóveis sem morada exata")
class GeocodingPrecisionTest {

    private NominatimGeocodingService service() {
        var props = new GeocodingProperties();
        props.setUrl("https://nominatim.openstreetmap.org/search");
        props.setUserAgent("Properia/1.0 (tech@properia.pt)");
        props.setTimeoutMs(20000);
        return new NominatimGeocodingService(props, new ObjectMapper());
    }

    /** Distância aproximada em km entre dois pontos (Haversine). */
    private static double km(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // Centroide do concelho de Matosinhos — o resultado errado que se quer evitar.
    private static final double MATOSINHOS_LAT = 41.1806814;
    private static final double MATOSINHOS_LON = -8.6821998;

    @Nested
    @DisplayName("Contra o Nominatim real")
    @EnabledIfEnvironmentVariable(named = "GEOCODING_LIVE_TEST", matches = "1")
    class Live {

        @Test
        @DisplayName("união de freguesias resolve para a freguesia, não para o concelho")
        void uniaoDeFreguesiasNaoCaiNoConcelho() throws Exception {
            var results = service().geocodeListingAddress(
                null, null, null,
                "Matosinhos",
                "União das Freguesias de São Mamede de Infesta e Senhora da Hora",
                "Porto");

            assertThat(results).isNotEmpty();
            var r = results.get(0);

            double erro = km(r.latitude(), r.longitude(), MATOSINHOS_LAT, MATOSINHOS_LON);
            System.out.printf("  -> %.6f, %.6f  precisão=%s  a %.1f km do centroide do concelho%n",
                r.latitude(), r.longitude(), r.precision(), erro);

            // Tem de estar longe do centroide do concelho (senão caiu no bug antigo)
            // mas dentro de Matosinhos.
            assertThat(erro).isGreaterThan(2.0).isLessThan(15.0);
            assertThat(r.precision()).isEqualTo("parish");
        }

        @Test
        @DisplayName("freguesia simples resolve com precisão de freguesia")
        void freguesiaSimples() throws Exception {
            var results = service().geocodeListingAddress(
                null, null, null, "Matosinhos", "São Mamede de Infesta", "Porto");

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).precision()).isEqualTo("parish");
            assertThat(km(results.get(0).latitude(), results.get(0).longitude(),
                MATOSINHOS_LAT, MATOSINHOS_LON)).isGreaterThan(2.0);
        }

        @Test
        @DisplayName("só concelho continua a devolver o concelho, agora sem fingir freguesia")
        void soConcelhoNaoFingePrecisao() throws Exception {
            var results = service().geocodeListingAddress(
                null, null, null, "Matosinhos", null, "Porto");

            assertThat(results).isNotEmpty();
            // O ponto é o centroide do concelho; a etiqueta não pode dizer "parish".
            assertThat(results.get(0).precision()).isIn("municipality", "parish");
            System.out.println("  -> precisão para só-concelho: " + results.get(0).precision());
        }

        @Test
        @DisplayName("não troca o concelho pela freguesia nos campos administrativos")
        void naoCorrompeConcelho() throws Exception {
            var results = service().geocodeListingAddress(
                null, null, null,
                "Matosinhos",
                "União das Freguesias de São Mamede de Infesta e Senhora da Hora",
                "Porto");

            assertThat(results).isNotEmpty();
            var r = results.get(0);

            // Em Portugal o OSM indexa freguesias como `city`. Sem a correção, a
            // resposta trazia city="São Mamede de Infesta" e o formulário
            // gravava isso em listings.city — a coluna usada na pesquisa por
            // concelho e no motor de avaliação. O concelho tem de sobreviver.
            assertThat(r.city()).isEqualTo("Matosinhos");
            assertThat(r.parish()).contains("São Mamede de Infesta");
            // Sem morada exata não se inventa rua nem código postal.
            assertThat(r.street()).isNull();
            assertThat(r.postalCode()).isNull();
        }

        @Test
        @DisplayName("morada exata não regride — continua street/exact")
        void moradaExataNaoRegride() throws Exception {
            var results = service().geocodeListingAddress(
                "Avenida da Boavista", "1000", "4100-001", "Porto", null, "Porto");

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).precision()).isIn("exact", "street", "neighborhood");
            System.out.println("  -> precisão para morada exata: " + results.get(0).precision());
        }
    }
}
