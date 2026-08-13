package pt.properia.api.shared.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que está a ser protegido: carregar as fotos de um anúncio caía no tier WRITE
 * (30/min). Um anúncio real traz 15–30 imagens e cada uma pode gastar mais do que
 * um pedido, por isso o lote batia no teto a meio e o anunciante via
 * "Demasiados pedidos. Aguarda 2s." — aconteceu em produção com 16 fotos.
 *
 * A ordem das regras é a parte frágil: os caminhos de media também casam com os
 * prefixos genéricos /api/advertiser/ e /api/listings/, portanto se a regra de
 * media for movida para baixo do WRITE deixa silenciosamente de ter efeito e o
 * limite antigo volta sem que nada falhe.
 */
@DisplayName("RateLimitingFilter — escalões por caminho")
class RateLimitingTierTest {

    private final RateLimitingFilter filter = new RateLimitingFilter();

    private HttpServletRequest req(String method, String path) {
        var r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        return r;
    }

    @Test
    @DisplayName("upload de fotos usa o escalão MEDIA, não o WRITE")
    void uploadDeFotosUsaMedia() {
        assertThat(filter.resolveTier(req("POST", "/api/media/upload-sessions")))
            .isEqualTo(RateLimitingFilter.Tier.MEDIA);
        assertThat(filter.resolveTier(req("POST", "/api/media/confirm")))
            .isEqualTo(RateLimitingFilter.Tier.MEDIA);
        // O fallback pelo servidor, que é o caminho realmente usado enquanto o
        // bucket R2 não tiver CORS configurado.
        assertThat(filter.resolveTier(req("POST", "/api/advertiser/listings/abc-123/media/upload")))
            .isEqualTo(RateLimitingFilter.Tier.MEDIA);
        // Apagar uma foto do lote também conta como media.
        assertThat(filter.resolveTier(req("DELETE", "/api/advertiser/listings/abc-123/media/m1")))
            .isEqualTo(RateLimitingFilter.Tier.MEDIA);
    }

    @Test
    @DisplayName("o escalão MEDIA acomoda uma galeria inteira")
    void mediaAcomodaGaleriaInteira() {
        // 30 fotos × 2 pedidos ainda cabem; o antigo limite de 30 não chegava
        // sequer para 16 fotos.
        assertThat(RateLimitingFilter.Tier.MEDIA.capacity).isGreaterThanOrEqualTo(60);
        assertThat(RateLimitingFilter.Tier.MEDIA.capacity)
            .isGreaterThan(RateLimitingFilter.Tier.WRITE.capacity);
    }

    @Test
    @DisplayName("as restantes escritas continuam no WRITE")
    void outrasEscritasNaoSobem() {
        assertThat(filter.resolveTier(req("POST", "/api/advertiser/listings")))
            .isEqualTo(RateLimitingFilter.Tier.WRITE);
        assertThat(filter.resolveTier(req("PATCH", "/api/listings/abc-123")))
            .isEqualTo(RateLimitingFilter.Tier.WRITE);
        assertThat(filter.resolveTier(req("POST", "/api/leads")))
            .isEqualTo(RateLimitingFilter.Tier.WRITE);
    }

    @Test
    @DisplayName("login e angariação pública mantêm os limites apertados")
    void limitesSensiveisIntactos() {
        assertThat(filter.resolveTier(req("POST", "/api/auth/login")))
            .isEqualTo(RateLimitingFilter.Tier.AUTH);
        assertThat(filter.resolveTier(req("POST", "/api/public/valuation/requests")))
            .isEqualTo(RateLimitingFilter.Tier.PUBLIC_FORM);
        assertThat(filter.resolveTier(req("POST", "/api/public/valuation/estimate")))
            .isEqualTo(RateLimitingFilter.Tier.ESTIMATE);
    }

    @Test
    @DisplayName("leituras de media não consomem o escalão de escrita")
    void leiturasNaoContam() {
        assertThat(filter.resolveTier(req("GET", "/api/listings/abc-123/media")))
            .isEqualTo(RateLimitingFilter.Tier.GLOBAL);
    }
}
