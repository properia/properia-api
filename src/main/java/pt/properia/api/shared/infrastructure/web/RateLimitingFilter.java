package pt.properia.api.shared.infrastructure.web;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter (Bucket4j, in-memory).
 *
 * Tiers (per IP):
 *   AUTH    — login / register / password-reset  → 5 req / 60 s  (brute-force protection)
 *   WRITE   — mutations on listings, media, etc. → 30 req / 60 s
 *   GLOBAL  — all other /api/ paths              → 300 req / 60 s
 *
 * Rate-limit headers (X-RateLimit-*) are always returned so the client knows
 * how many tokens remain and when to retry.
 *
 * Note: in-memory buckets are per-instance. For multi-instance deployments,
 * replace with Bucket4j + Redis (bucket4j-redis module).
 */
@Component
@Order(1)
public class RateLimitingFilter extends OncePerRequestFilter {

    // Bucket maps keyed by "tier:ip". O holder guarda o último acesso para que o sweep
    // periódico remova entradas inativas — senão o mapa cresceria sem limite (correção #10).
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    private static final long IDLE_EVICT_MS = Duration.ofMinutes(10).toMillis();

    private static final class BucketEntry {
        final Bucket bucket;
        final AtomicLong lastAccessMs;
        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccessMs = new AtomicLong(System.currentTimeMillis());
        }
    }

    /** Remove buckets inativos há mais de 10 min, limitando o uso de memória por IP. */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    void evictIdleBuckets() {
        var cutoff = System.currentTimeMillis() - IDLE_EVICT_MS;
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessMs.get() < cutoff);
    }

    // ── Tier definitions ──────────────────────────────────────────────────────

    private enum Tier {
        AUTH(5, Duration.ofMinutes(1)),
        WRITE(30, Duration.ofMinutes(1)),
        GLOBAL(300, Duration.ofMinutes(1));

        final long capacity;
        final Duration refillPeriod;

        Tier(long capacity, Duration refillPeriod) {
            this.capacity = capacity;
            this.refillPeriod = refillPeriod;
        }
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only rate-limit API paths
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var ip   = resolveClientIp(request);
        var tier = resolveTier(request);
        var entry = buckets.computeIfAbsent(tier.name() + ":" + ip, k -> new BucketEntry(newBucket(tier)));
        entry.lastAccessMs.set(System.currentTimeMillis());

        var probe = entry.bucket.tryConsumeAndReturnRemaining(1);

        response.setHeader("X-RateLimit-Limit",     String.valueOf(tier.capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));

        if (!probe.isConsumed()) {
            long retryAfterSec = (probe.getNanosToWaitForRefill() / 1_000_000_000L) + 1;
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Demasiados pedidos. Aguarda " + retryAfterSec + "s.\"}}"
            );
            return;
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tier resolveTier(HttpServletRequest req) {
        var path   = req.getRequestURI();
        var method = req.getMethod();

        // Auth endpoints — tightest limit
        if (path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/password/")) {
            return Tier.AUTH;
        }

        // Mutations on user-generated content
        if (isWriteMethod(method) && (
                path.startsWith("/api/advertiser/")
                || path.startsWith("/api/listings/")
                || path.startsWith("/api/media/")
                || path.startsWith("/api/leads")
                || path.startsWith("/api/visitas"))) {
            return Tier.WRITE;
        }

        return Tier.GLOBAL;
    }

    private static boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method)
            || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method)
            || "DELETE".equalsIgnoreCase(method);
    }

    /**
     * Resolves real client IP, respecting Cloudflare's CF-Connecting-IP header.
     * Falls back to X-Forwarded-For, then RemoteAddr.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        // Cloudflare sets CF-Connecting-IP with the real visitor IP
        var cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();

        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private static Bucket newBucket(Tier tier) {
        var bandwidth = Bandwidth.builder()
            .capacity(tier.capacity)
            .refillGreedy(tier.capacity, tier.refillPeriod)
            .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
