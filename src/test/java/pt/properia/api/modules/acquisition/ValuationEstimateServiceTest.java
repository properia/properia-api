package pt.properia.api.modules.acquisition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pt.properia.api.modules.acquisition.application.MarketPriceRepository;
import pt.properia.api.modules.acquisition.application.MarketSample;
import pt.properia.api.modules.acquisition.application.ValuationEstimateService;
import pt.properia.api.modules.acquisition.application.ValuationInput;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitário puro da cascata do motor de estimativa — sem Spring, sem base
 * de dados, sem Docker (a suite de integração está bloqueada nesta máquina por
 * incompatibilidade Testcontainers/Docker Desktop, e a cascata é precisamente a
 * parte que não pode ficar por testar).
 *
 * O que está a ser protegido: a ordem da cascata e a guarda de amostra mínima.
 * Se um nível insuficiente passar, um proprietário recebe por email um valor
 * calculado a partir de dois anúncios — com toda a aparência de rigor e nenhum
 * fundamento. Os casos negativos abaixo valem mais do que os positivos.
 */
@DisplayName("Motor de estimativa — cascata de fontes de preço")
class ValuationEstimateServiceTest {

    /** Stub que regista as chamadas feitas, para verificar a ordem da cascata. */
    private static class StubMarketPrices implements MarketPriceRepository {
        final List<String> calls = new ArrayList<>();
        Optional<MarketSample> parish = Optional.empty();
        Optional<MarketSample> municipality = Optional.empty();
        Optional<MarketSample> benchmark = Optional.empty();

        @Override
        public Optional<MarketSample> listingsMedianByParish(
                String propertyType, String businessType, String municipality, String parish) {
            calls.add("parish");
            return this.parish;
        }

        @Override
        public Optional<MarketSample> listingsMedianByMunicipality(
                String propertyType, String businessType, String municipality) {
            calls.add("municipality");
            return this.municipality;
        }

        @Override
        public Optional<MarketSample> benchmark(
                String propertyType, String businessType,
                String district, String municipality, String parish, Integer bedrooms) {
            calls.add("benchmark");
            return this.benchmark;
        }
    }

    private static MarketSample sample(String ppm2, int size, String source) {
        return new MarketSample(new BigDecimal(ppm2), size, source, "âmbito");
    }

    /** T2 de 100 m², em bom estado, sem características que ajustem o preço. */
    private static ValuationInput neutralInput() {
        return new ValuationInput(
            "apartment", "sale", "Porto", "Porto", "Ramalde",
            2, new BigDecimal("100"),
            "used_good", null, null, null);
    }

    @Nested
    @DisplayName("Ordem da cascata")
    class Cascade {

        @Test
        @DisplayName("a freguesia ganha e as fontes mais genéricas nem são consultadas")
        void freguesiaTemPrecedencia() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 25, "listings_parish"));
            stub.municipality = Optional.of(sample("1500", 500, "listings_municipality"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.available()).isTrue();
            assertThat(result.source()).isEqualTo("listings_parish");
            assertThat(stub.calls).containsExactly("parish");
        }

        @Test
        @DisplayName("freguesia sem amostra suficiente desce para o concelho")
        void desceParaConcelho() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 2, "listings_parish"));   // < 3
            stub.municipality = Optional.of(sample("1500", 40, "listings_municipality"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.source()).isEqualTo("listings_municipality");
            assertThat(stub.calls).containsExactly("parish", "municipality");
        }

        @Test
        @DisplayName("sem anúncios comparáveis, cai no benchmark do INE")
        void desceParaBenchmark() {
            var stub = new StubMarketPrices();
            stub.benchmark = Optional.of(sample("1300", 120, "market_benchmark"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.source()).isEqualTo("market_benchmark");
            assertThat(stub.calls).containsExactly("parish", "municipality", "benchmark");
        }

        @Test
        @DisplayName("esgotada a cascata, não há estimativa — nunca um palpite")
        void semFonteNaoHaEstimativa() {
            var stub = new StubMarketPrices();

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.available()).isFalse();
            assertThat(result.min()).isNull();
            assertThat(result.max()).isNull();
            assertThat(result.source()).isEqualTo("none");
            assertThat(result.inputs()).containsEntry("rejectedReason", "no_comparables");
        }

        @Test
        @DisplayName("sem freguesia declarada, salta o nível mais preciso sem falhar")
        void semFreguesiaSaltaNivel() {
            var stub = new StubMarketPrices();
            stub.municipality = Optional.of(sample("1500", 40, "listings_municipality"));

            var input = new ValuationInput(
                "apartment", "sale", "Porto", "Porto", null,
                2, new BigDecimal("100"), "used_good", null, null, null);

            var result = new ValuationEstimateService(stub).estimate(input);

            assertThat(result.available()).isTrue();
            assertThat(stub.calls).containsExactly("municipality");
        }
    }

    @Nested
    @DisplayName("Guarda de amostra mínima e sanidade")
    class Guards {

        @Test
        @DisplayName("dois anúncios não são amostra")
        void rejeitaAmostraInsuficiente() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 2, "listings_parish"));
            stub.municipality = Optional.of(sample("2000", 2, "listings_municipality"));
            stub.benchmark = Optional.of(sample("2000", 2, "market_benchmark"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.available()).isFalse();
        }

        @Test
        @DisplayName("exatamente 3 anúncios já passa — é o limiar documentado")
        void aceitaExatamenteOMinimo() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 3, "listings_parish"));

            assertThat(new ValuationEstimateService(stub).estimate(neutralInput()).available())
                .isTrue();
        }

        @Test
        @DisplayName("um €/m² absurdo é dado corrompido, não uma zona cara")
        void rejeitaPrecoForaDeEscala() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("999999", 50, "listings_parish"));
            stub.municipality = Optional.of(sample("1", 50, "listings_municipality"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.available()).isFalse();
        }

        @Test
        @DisplayName("sem área não há cálculo possível")
        void rejeitaEntradaIncompleta() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 50, "listings_parish"));

            var input = new ValuationInput(
                "apartment", "sale", "Porto", "Porto", "Ramalde",
                2, null, "used_good", null, null, null);

            var result = new ValuationEstimateService(stub).estimate(input);

            assertThat(result.available()).isFalse();
            assertThat(result.inputs()).containsEntry("rejectedReason", "insufficient_input");
            assertThat(stub.calls).isEmpty();
        }
    }

    @Nested
    @DisplayName("Confiança e intervalo")
    class ConfidenceAndRange {

        @Test
        @DisplayName("muitos comparáveis na freguesia dão confiança alta e intervalo estreito")
        void amostraGrandeNaFreguesiaDaConfiancaAlta() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 30, "listings_parish"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.confidence()).isEqualTo("high");
        }

        @Test
        @DisplayName("o concelho nunca dá confiança alta, por muitos anúncios que tenha")
        void concelhoNuncaDaConfiancaAlta() {
            var stub = new StubMarketPrices();
            stub.municipality = Optional.of(sample("2000", 5000, "listings_municipality"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.confidence()).isEqualTo("medium");
        }

        @Test
        @DisplayName("menos confiança implica intervalo mais largo — é como se comunica incerteza")
        void menosConfiancaAlargaOIntervalo() {
            var alta = new StubMarketPrices();
            alta.parish = Optional.of(sample("2000", 40, "listings_parish"));
            var estimativaAlta = new ValuationEstimateService(alta).estimate(neutralInput());

            var baixa = new StubMarketPrices();
            baixa.parish = Optional.of(sample("2000", 3, "listings_parish"));
            var estimativaBaixa = new ValuationEstimateService(baixa).estimate(neutralInput());

            var larguraAlta = estimativaAlta.max().subtract(estimativaAlta.min());
            var larguraBaixa = estimativaBaixa.max().subtract(estimativaBaixa.min());

            assertThat(estimativaAlta.confidence()).isEqualTo("high");
            assertThat(estimativaBaixa.confidence()).isEqualTo("low");
            assertThat(larguraBaixa).isGreaterThan(larguraAlta);
        }

        @Test
        @DisplayName("o resultado é sempre um intervalo, nunca um valor único")
        void devolveSempreIntervalo() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 40, "listings_parish"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.min()).isLessThan(result.max());
        }

        @Test
        @DisplayName("2000 €/m² × 85 m² sem ajustes → intervalo centrado em 170.000 €")
        void valorCentralCorresponde() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 40, "listings_parish"));

            // 85 m² é exatamente a área típica de um T2: com estado 'used_good' e
            // sem andar nem certificado declarados, todos os fatores valem 1.0 e
            // o resultado tem de ser o preço da zona × área, sem desvio.
            var result = new ValuationEstimateService(stub).estimate(new ValuationInput(
                "apartment", "sale", "Porto", "Porto", "Ramalde",
                2, new BigDecimal("85"), "used_good", null, null, null));

            var centro = result.min().add(result.max())
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);

            assertThat(centro).isCloseTo(new BigDecimal("170000"),
                org.assertj.core.data.Offset.offset(new BigDecimal("1000")));
        }

        @Test
        @DisplayName("os valores são arredondados — '247.318 €' finge uma precisão que não existe")
        void valoresSaoArredondados() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2137.44", 40, "listings_parish"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());

            assertThat(result.min().remainder(BigDecimal.valueOf(500)).signum()).isZero();
            assertThat(result.max().remainder(BigDecimal.valueOf(500)).signum()).isZero();
        }
    }

    @Nested
    @DisplayName("Auditabilidade")
    class Auditability {

        @Test
        @DisplayName("guarda entradas, fatores e cascata — sem isto não há defesa numa reclamação")
        void registaEntradasEFatores() {
            var stub = new StubMarketPrices();
            stub.parish = Optional.of(sample("2000", 2, "listings_parish"));
            stub.municipality = Optional.of(sample("1800", 40, "listings_municipality"));

            var result = new ValuationEstimateService(stub).estimate(neutralInput());
            var inputs = result.inputs();

            assertThat(inputs).containsKeys(
                "propertyType", "municipality", "parish", "usableAreaM2", "conditionStatus",
                "basePricePerM2", "baseSource", "baseSampleSize",
                "factors", "confidence", "bandPct", "adjustedPricePerM2",
                "cascade", "engineVersion");

            @SuppressWarnings("unchecked")
            var cascade = (List<java.util.Map<String, Object>>) inputs.get("cascade");
            assertThat(cascade).hasSize(2);
            assertThat(cascade.get(0)).containsEntry("source", "listings_parish");
            assertThat(cascade.get(0)).containsEntry("accepted", false);
            assertThat(cascade.get(1)).containsEntry("accepted", true);
        }

        @Test
        @DisplayName("o estado de conservação move mesmo o resultado")
        void ajustesRefletemSeNoResultado() {
            var stubBom = new StubMarketPrices();
            stubBom.parish = Optional.of(sample("2000", 40, "listings_parish"));
            var bom = new ValuationEstimateService(stubBom).estimate(neutralInput());

            var stubMau = new StubMarketPrices();
            stubMau.parish = Optional.of(sample("2000", 40, "listings_parish"));
            var paraRecuperar = new ValuationEstimateService(stubMau).estimate(new ValuationInput(
                "apartment", "sale", "Porto", "Porto", "Ramalde",
                2, new BigDecimal("100"), "to_renovate", null, null, null));

            assertThat(paraRecuperar.max()).isLessThan(bom.min());
        }
    }
}
