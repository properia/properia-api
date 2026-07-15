package pt.properia.api.shared.infrastructure;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Cache in-process (Caffeine) para endpoints públicos read-only e caros.
 * Memory-bounded (maximumSize) e com TTL curto — a "frescura" perdida é aceitável
 * (marketing/SEO), e o ganho é evitar queries repetidas numa instância pequena.
 *
 * Caches:
 *  - "publicFeatured"  → GET /api/listings/featured (subquery de views por linha)
 *  - "publicShowcase"  → GET /api/public/advertisers/showcase (logos de agências)
 *
 * Invalidação: por TTL apenas (não há eviction manual — a stale window é pequena).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String FEATURED = "publicFeatured";
    public static final String SHOWCASE = "publicShowcase";

    @Bean
    public CacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        // Caches minúsculos (poucas chaves): default de 128 entradas cobre folgado.
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(Duration.ofMinutes(3)));
        manager.setCacheNames(java.util.List.of(FEATURED, SHOWCASE));
        return manager;
    }
}
