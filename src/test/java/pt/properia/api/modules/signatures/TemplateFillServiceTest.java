package pt.properia.api.modules.signatures;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pt.properia.api.modules.enrichment.vision.infrastructure.OpenAIProperties;
import pt.properia.api.modules.signatures.application.TemplateFillService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sem OpenAI configurada, o serviço usa o fallback heurístico (correspondência por
 * tokens do nome do campo). Valida que coloca o valor certo e nunca inventa.
 */
class TemplateFillServiceTest {

    private final TemplateFillService service = new TemplateFillService(new OpenAIProperties(), new ObjectMapper());

    @Test
    void heuristicMapsFieldByName() {
        var data = Map.of(
            "Nome do cliente", "Ana Conceição",
            "Licença AMI", "AMI-12345");

        var result = service.suggest(List.of("nome_cliente", "licenca_ami", "campo_sem_dados"), data);

        assertEquals("Ana Conceição", result.get("nome_cliente"));
        assertEquals("AMI-12345", result.get("licenca_ami"));
        assertEquals("", result.get("campo_sem_dados"), "Campo sem dado correspondente fica vazio (nunca inventado)");
    }

    @Test
    void emptyFieldsReturnsEmpty() {
        assertTrue(service.suggest(List.of(), Map.of("x", "y")).isEmpty());
    }
}
