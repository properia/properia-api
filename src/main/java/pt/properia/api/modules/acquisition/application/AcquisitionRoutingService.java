package pt.properia.api.modules.acquisition.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Decide que anunciante recebe um lead de angariação.
 *
 * Versão 1, deliberadamente mínima: uma conta-casa configurada por ambiente.
 * As regras por zona (acquisition_routing_rules, freguesia → concelho →
 * distrito, com tetos mensais e ligação aos créditos) são a fase seguinte e não
 * bloqueiam o arranque do canal.
 *
 * Se não houver conta-casa configurada, o lead fica POR ENCAMINHAR
 * (advertiser_id NULL). É melhor do que a alternativa: atribuir a um anunciante
 * arbitrário significa entregar um contacto real a quem não devia recebê-lo, o
 * que além de mau negócio é um problema de proteção de dados.
 */
@Service
public class AcquisitionRoutingService {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionRoutingService.class);

    private final UUID houseAdvertiserId;

    public AcquisitionRoutingService(
        @Value("${properia.acquisition.house-advertiser-id:}") String houseAdvertiserId
    ) {
        this.houseAdvertiserId = parseUuid(houseAdvertiserId);
        if (this.houseAdvertiserId == null) {
            log.warn("Encaminhamento de angariação sem conta-casa configurada "
                + "(properia.acquisition.house-advertiser-id). Os leads de proprietário "
                + "ficam na fila por encaminhar até existir regra.");
        }
    }

    /**
     * @return anunciante a quem entregar o lead, ou vazio se ficar por encaminhar.
     */
    public Optional<UUID> routeOwnerLead(String district, String municipality, String parish) {
        // Assinatura já preparada para as regras por zona da fase seguinte; por
        // agora a decisão não depende da localização.
        return Optional.ofNullable(houseAdvertiserId);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            log.error("properia.acquisition.house-advertiser-id não é um UUID válido: '{}'", value);
            return null;
        }
    }
}
