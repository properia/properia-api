package pt.properia.api.modules.listings.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Barreira mínima de qualidade antes de um anúncio ficar visível ao público. Sem isto,
 * qualquer conta nova conseguia publicar um anúncio vazio (sem preço, sem fotos, sem
 * localização) imediatamente após o registo, contornando por completo o wizard de criação
 * (que só corre no cliente) com uma chamada direta à API.
 *
 * IMPORTANTE: o wizard de criação (listings-admin-page.tsx, `publishValidation`) já impõe
 * regras muito mais ricas e legalmente específicas — certificado energético (DL 118/2013),
 * caução máxima (Lei 13/2019), plausibilidade de preço/m², etc. Este validador NÃO duplica
 * a maioria dessas regras (ficariam desalinhadas e a divergir com o tempo); é deliberadamente
 * um subconjunto mais fraco — só bloqueia o caso degenerado (anúncio vazio/spam via API
 * direta), nunca mais restritivo do que o que o cliente já validou. Essas regras continuam
 * a viver só no cliente e PODEM ser contornadas por uma chamada direta à API — gap conhecido,
 * aceite para a maioria dos casos. EXCEÇÃO: o registo RNAL de Alojamento Local é grave
 * o suficiente (exposição legal) para ter o seu próprio backstop aqui — ver abaixo.
 *
 * Fonte única partilhada entre PublishListingUseCase (POST .../publish) e
 * PatchListingService (PATCH .../listings/{id} com status="published") — os dois
 * caminhos que podem publicar um anúncio.
 */
@Component
public class ListingPublishReadinessValidator {

    private final JdbcClient jdbc;

    public ListingPublishReadinessValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void assertReadyToPublish(Listing listing) {
        var missing = new ArrayList<String>();

        if (listing.getPriceAmount() == null || listing.getPriceAmount().signum() <= 0) {
            missing.add("preço");
        }
        // Mesmo critério "OR" do cliente (cidade OU distrito OU freguesia) — não pedir mais do que ele.
        if (isBlank(listing.getCity()) && isBlank(listing.getDistrict()) && isBlank(listing.getParish())) {
            missing.add("localização (cidade, distrito ou freguesia)");
        }
        // Coordenadas são obrigatórias: sem elas o anúncio não aparece no mapa nem na pesquisa
        // por zona/trajeto, e não há dados para a análise de zona. O cliente já resolve isto via
        // geocoding automático — aqui é só o backstop contra a chamada direta à API.
        if (listing.getLatitude() == null || listing.getLongitude() == null) {
            missing.add("localização confirmada no mapa (coordenadas)");
        }
        var description = listing.getDescriptionRaw() != null ? listing.getDescriptionRaw() : listing.getDescriptionShort();
        if (isBlank(description)) {
            missing.add("descrição");
        }
        // Alojamento Local é obrigado por lei (DL 128/2014, na redação atual) a exibir o nº
        // de registo no RNAL em qualquer anúncio — sem ele o anúncio é ilegal. Ao contrário
        // das outras regras legais "só de cliente" mencionadas no Javadoc desta classe, esta
        // é suficientemente grave (exposição legal do anunciante e do portal) para justificar
        // o backstop aqui também.
        if ("holiday_rent".equals(listing.getBusinessType()) && isBlank(listing.getAlRegistrationNumber())) {
            missing.add("número de registo RNAL (Alojamento Local)");
        }
        if (countPhotos(listing.getId()) < 1) {
            missing.add("pelo menos uma foto");
        }

        if (!missing.isEmpty()) {
            throw new DomainException("LISTING_INCOMPLETE",
                "Este anúncio ainda não pode ser publicado. Falta: " + String.join(", ", missing) + ".", 422);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int countPhotos(UUID listingId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM properia.listing_media
                WHERE listing_id = :lid AND media_type = 'image'::properia.media_type
                """)
            .param("lid", listingId)
            .query(Integer.class)
            .single();
    }
}
