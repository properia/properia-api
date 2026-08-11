package pt.properia.api.modules.acquisition.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.properia.api.modules.acquisition.application.MarketPriceRepository;
import pt.properia.api.modules.acquisition.application.MarketSample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Fontes de preço reais. Ver {@link MarketPriceRepository} para a ordem da cascata.
 *
 * Nota sobre nomenclatura: `properia.listings` não tem coluna `municipality` —
 * guarda o concelho em `city`. `market_price_benchmarks` usa `municipality`.
 * A tradução entre os dois é feita aqui e em mais lado nenhum.
 */
@Repository
public class JdbcMarketPriceRepository implements MarketPriceRepository {

    private final JdbcClient jdbc;

    public JdbcMarketPriceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MarketSample> listingsMedianByParish(
            String propertyType, String businessType, String municipality, String parish) {

        if (isBlank(municipality) || isBlank(parish)) return Optional.empty();

        return queryListingsMedian(propertyType, businessType, municipality, parish)
            .map(row -> new MarketSample(
                row.ppm2(), row.sampleSize(), "listings_parish", "Freguesia de " + parish));
    }

    @Override
    public Optional<MarketSample> listingsMedianByMunicipality(
            String propertyType, String businessType, String municipality) {

        if (isBlank(municipality)) return Optional.empty();

        return queryListingsMedian(propertyType, businessType, municipality, null)
            .map(row -> new MarketSample(
                row.ppm2(), row.sampleSize(), "listings_municipality", municipality));
    }

    private record MedianRow(BigDecimal ppm2, int sampleSize) {}

    private Optional<MedianRow> queryListingsMedian(
            String propertyType, String businessType, String municipality, String parish) {

        var rows = jdbc.sql("""
                SELECT COUNT(*)::int AS sample_size,
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY l.price_amount / l.usable_area_m2
                       ) AS median_ppm2
                FROM properia.listings l
                WHERE l.status = 'published'
                  AND l.business_type::text = :businessType
                  AND l.property_type::text = :propertyType
                  AND lower(l.city) = lower(:municipality)
                  AND (:parish::text IS NULL OR lower(l.parish) = lower(:parish))
                  AND l.price_amount > 0
                  AND l.usable_area_m2 > 0
                """)
            .param("businessType", businessType)
            .param("propertyType", propertyType)
            .param("municipality", municipality)
            .param("parish", parish)
            .query((rs, n) -> {
                var median = rs.getBigDecimal("median_ppm2");
                return new MedianRow(
                    median != null ? median.setScale(2, RoundingMode.HALF_UP) : null,
                    rs.getInt("sample_size"));
            })
            .list();

        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        return row.ppm2() == null ? Optional.empty() : Optional.of(row);
    }

    @Override
    public Optional<MarketSample> benchmark(
            String propertyType, String businessType,
            String district, String municipality, String parish, Integer bedrooms) {

        // Cascata interna de granularidade. O ORDER BY faz o trabalho: a linha
        // mais específica que corresponda ganha, e entre iguais vence a mais
        // recente. Evita três viagens à base de dados.
        var rows = jdbc.sql("""
                SELECT b.median_price_per_m2,
                       b.sample_size,
                       b.granularity::text AS granularity,
                       b.benchmark_label
                FROM properia.market_price_benchmarks b
                WHERE b.is_active = true
                  AND b.business_type::text = :businessType
                  AND b.property_type::text = :propertyType
                  AND b.median_price_per_m2 IS NOT NULL
                  AND (b.valid_to IS NULL OR b.valid_to >= CURRENT_DATE)
                  AND (
                        (b.granularity = 'parish'       AND :parish::text IS NOT NULL
                                                        AND lower(b.parish) = lower(:parish))
                     OR (b.granularity = 'municipality' AND :municipality::text IS NOT NULL
                                                        AND lower(b.municipality) = lower(:municipality))
                     OR (b.granularity = 'district'     AND :district::text IS NOT NULL
                                                        AND lower(b.district) = lower(:district))
                  )
                  AND (b.bedrooms_min IS NULL OR :bedrooms::int IS NULL OR :bedrooms::int >= b.bedrooms_min)
                  AND (b.bedrooms_max IS NULL OR :bedrooms::int IS NULL OR :bedrooms::int <= b.bedrooms_max)
                ORDER BY CASE b.granularity
                             WHEN 'parish'       THEN 1
                             WHEN 'municipality' THEN 2
                             WHEN 'district'     THEN 3
                             ELSE 4
                         END,
                         -- Uma linha com faixa de quartos definida é mais específica
                         -- do que a linha genérica do mesmo âmbito.
                         (b.bedrooms_min IS NULL) ASC,
                         b.benchmark_date DESC
                LIMIT 1
                """)
            .param("businessType", businessType)
            .param("propertyType", propertyType)
            .param("district", district)
            .param("municipality", municipality)
            .param("parish", parish)
            .param("bedrooms", bedrooms)
            .query((rs, n) -> {
                var median = rs.getBigDecimal("median_price_per_m2");
                var label = rs.getString("benchmark_label");
                return new MarketSample(
                    median != null ? median.setScale(2, RoundingMode.HALF_UP) : null,
                    rs.getInt("sample_size"),
                    "market_benchmark",
                    label);
            })
            .list();

        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
