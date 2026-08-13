package pt.properia.api.modules.advertiser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que está a ser protegido: a home mostra "quem já usa a Properia" a partir da
 * lista de anunciantes. Apareciam lá contas internas e de teste, que não são
 * clientes — má prova social e má imagem para quem chega ao site.
 *
 * A tentação era uma lista de nomes proibidos. Não se fez: partia-se assim que
 * alguém renomeasse uma conta, e obrigava a mexer em código sempre que surgisse
 * outra conta interna. As duas regras que ficaram são estruturais.
 *
 * O teste lê o SQL do controlador em vez de correr a query: garante que as
 * condições não desaparecem numa futura reescrita, sem precisar de base de dados.
 */
@DisplayName("Showcase de agências na home")
class ShowcaseFilterTest {

    private String sqlDoControlador() throws Exception {
        var p = Path.of("src/main/java/pt/properia/api/modules/advertiser/interfaces/PublicAdvertiserController.java");
        var fonte = Files.readString(p);
        int i = fonte.indexOf("/api/public/advertisers/showcase");
        assertThat(i).as("endpoint do showcase não encontrado").isGreaterThan(0);
        // Corta no endpoint seguinte para não apanhar outras queries do ficheiro.
        int fim = fonte.indexOf("@GetMapping", i + 10);
        return fonte.substring(i, fim > 0 ? fim : fonte.length());
    }

    @Test
    @DisplayName("exige pelo menos um imóvel publicado")
    void exigeImovelPublicado() throws Exception {
        var sql = sqlDoControlador();
        assertThat(sql).contains("FROM properia.listings l");
        assertThat(sql).contains("l.status = 'published'");
        assertThat(sql).contains("l.advertiser_id = a.id");
    }

    @Test
    @DisplayName("respeita o opt-out explícito")
    void respeitaOptOut() throws Exception {
        assertThat(sqlDoControlador()).contains("hideFromShowcase");
    }

    @Test
    @DisplayName("mantém as exclusões que já existiam")
    void mantemExclusoesAntigas() throws Exception {
        var sql = sqlDoControlador();
        assertThat(sql).contains("a.is_active = true");
        assertThat(sql).contains("advertiser_type != 'private_owner'");
        assertThat(sql).contains("a.brand_name IS NOT NULL");
    }

    @Test
    @DisplayName("não filtra por nomes — uma lista de nomes partia-se ao renomear")
    void naoFiltraPorNome() throws Exception {
        var sql = sqlDoControlador().toLowerCase();
        for (var nome : new String[]{"raphael", "properia qa", "teste", "iarussi"}) {
            assertThat(sql).as("filtro por nome encontrado: " + nome).doesNotContain(nome);
        }
    }
}
