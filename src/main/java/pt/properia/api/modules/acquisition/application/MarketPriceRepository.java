package pt.properia.api.modules.acquisition.application;

import java.util.Optional;

/**
 * Fontes de preço de mercado, por ordem decrescente de precisão.
 *
 * Existe como interface para que a cascata do {@link ValuationEstimateService}
 * seja testável sem base de dados nem Docker — a suite de integração está
 * bloqueada nesta máquina e a lógica de cascata é precisamente a parte que não
 * pode ficar por testar.
 */
public interface MarketPriceRepository {

    /** Mediana do €/m² de anúncios publicados na mesma freguesia e tipo de imóvel. */
    Optional<MarketSample> listingsMedianByParish(
        String propertyType, String businessType, String municipality, String parish);

    /** Idem, alargado ao concelho. */
    Optional<MarketSample> listingsMedianByMunicipality(
        String propertyType, String businessType, String municipality);

    /**
     * Benchmark oficial (INE / dados.gov) da tabela market_price_benchmarks,
     * com cascata interna de granularidade freguesia → concelho → distrito.
     */
    Optional<MarketSample> benchmark(
        String propertyType, String businessType,
        String district, String municipality, String parish, Integer bedrooms);
}
