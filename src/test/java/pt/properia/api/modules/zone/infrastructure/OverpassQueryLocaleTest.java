package pt.properia.api.modules.zone.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O bug que este teste tranca: String.format sem locale explícita usa a locale do
 * sistema. Em pt-PT (e em qualquer locale de vírgula decimal) o %.6f das coordenadas
 * saía como "41,191905", partindo a sintaxe da query. O Overpass respondia HTTP 400,
 * o cliente apanhava a exceção e devolvia listas vazias — resultado: TODOS os imóveis
 * ficavam com zona "0 POIs" e nada na UI ou nos dados indicava que havia um erro.
 *
 * Foi encontrado ao correr a análise de zona numa máquina em português.
 */
@DisplayName("Overpass — a query não pode depender da locale do sistema")
class OverpassQueryLocaleTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    @DisplayName("coordenadas usam ponto decimal mesmo com locale de vírgula")
    void coordenadasUsamPontoDecimal() {
        Locale.setDefault(Locale.of("pt", "PT"));

        var clause = OverpassPoiClient.aroundClause(1500, 41.191905, -8.610439);

        assertThat(clause).isEqualTo("(around:1500,41.191905,-8.610439)");
        // Duas vírgulas e nem uma a mais: separam os três argumentos (raio, lat, lng).
        // Uma terceira significaria que uma coordenada se partiu em dois números.
        assertThat(clause.chars().filter(c -> c == ',').count()).isEqualTo(2);
    }

    @Test
    @DisplayName("mesma saída em qualquer locale")
    void estavelEntreLocales() {
        Locale.setDefault(Locale.GERMANY);
        var alema = OverpassPoiClient.aroundClause(500, 41.15, -8.68);
        Locale.setDefault(Locale.US);
        var americana = OverpassPoiClient.aroundClause(500, 41.15, -8.68);

        assertThat(alema).isEqualTo(americana);
    }
}
