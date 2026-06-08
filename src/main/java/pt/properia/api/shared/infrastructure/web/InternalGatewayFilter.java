package pt.properia.api.shared.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Blocks direct access to the Java API that bypasses the Next.js gateway.
 *
 * Every request forwarded by the Next.js proxy includes the header:
 *   X-Api-Gateway-Token: <INTERNAL_API_SECRET>
 *
 * Requests missing or with the wrong token are rejected with 403.
 * This prevents attackers who discover the backend URL from bypassing
 * rate limiting, CORS, and the Next.js middleware.
 *
 * Set the same secret in both services:
 *   Backend:  INTERNAL_API_SECRET env var
 *   Frontend: INTERNAL_API_SECRET env var (injected via Next.js middleware)
 *
 * If INTERNAL_API_SECRET is blank/unset the check is disabled (dev mode).
 */
@Component
@Order(0)
public class InternalGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalGatewayFilter.class);
    private static final String HEADER = "X-Api-Gateway-Token";

    @Value("${properia.security.internal-api-secret:}")
    private String expectedSecret;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only enforce on /api/ paths; let actuator and static resources through
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Check disabled when no secret is configured (local dev / test)
        if (expectedSecret == null || expectedSecret.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        var token = request.getHeader(HEADER);
        if (!expectedSecret.equals(token)) {
            log.warn("Gateway token mismatch — possible direct API access from ip={} path={}",
                request.getRemoteAddr(), request.getRequestURI());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Acesso não autorizado.\"}}"
            );
            return;
        }

        chain.doFilter(request, response);
    }
}
