package pt.properia.api.shared.infrastructure.web.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads the JWT from the httpOnly cookie (properia_session),
 * validates it, and populates the Spring Security context.
 *
 * Falls back to Authorization: Bearer header for API clients / tests.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JwtProperties props;

    public JwtAuthenticationFilter(JwtService jwtService, JwtProperties props) {
        this.jwtService = jwtService;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        extractToken(request).ifPresent(token ->
            jwtService.validateToken(token).ifPresent(claims -> {
                var authority = new SimpleGrantedAuthority("ROLE_" + claims.role().toUpperCase());
                var auth = new UsernamePasswordAuthenticationToken(
                    claims,        // principal — accessible via @AuthenticationPrincipal JwtClaims
                    null,
                    List.of(authority)
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            })
        );

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        // 1. httpOnly cookie (primary — browser clients)
        if (request.getCookies() != null) {
            var cookieToken = Arrays.stream(request.getCookies())
                .filter(c -> props.getCookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> !v.isBlank())
                .findFirst();
            if (cookieToken.isPresent()) return cookieToken;
        }

        // 2. Authorization: Bearer header (fallback — API clients, tests)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return Optional.of(authHeader.substring(7));
        }

        return Optional.empty();
    }
}
