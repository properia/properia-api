package pt.properia.api.shared.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import pt.properia.api.shared.infrastructure.web.jwt.JwtAuthenticationFilter;

import java.util.List;

/**
 * Spring Security 6 configuration.
 *
 * Auth strategy: stateless JWT via httpOnly cookie.
 * The cookie name matches the Next.js session cookie (properia_session)
 * so tokens are interoperable during the Strangler Fig migration.
 *
 * Public routes: health, swagger, public search, public listing detail.
 * Protected routes: everything under /api/advertiser/*, /api/auth/me, etc.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            // Stateless — JWT in httpOnly cookie, no server-side session
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Disable CSRF — protected by httpOnly + SameSite=Lax cookie
            .csrf(AbstractHttpConfigurer::disable)

            // CORS — controlled per environment via CorsConfigurationSource
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Route authorization rules
            .authorizeHttpRequests(auth -> auth
                // Infrastructure
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Public health + API root
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()

                // Public listing and search
                .requestMatchers(HttpMethod.GET, "/api/listings/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/listings/*/view").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/search/**").permitAll()

                // Dev local storage media serving (public so listing images render on public pages)
                .requestMatchers(HttpMethod.GET, "/api/local-storage/media/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/search/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/geocoding/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/locations/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()

                // Auth endpoints (login, register, OAuth, me, logout)
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/register").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()
                .requestMatchers("/api/auth/me").permitAll()
                .requestMatchers("/api/auth/google/**").permitAll()
                .requestMatchers("/api/auth/oauth/**").permitAll()
                .requestMatchers("/api/auth/callback").permitAll()
                .requestMatchers("/api/auth/password/forgot").permitAll()
                .requestMatchers("/api/auth/password/reset").permitAll()
                .requestMatchers("/api/auth/email-verification/**").permitAll()

                // Buyer consent (token-based, public)
                .requestMatchers("/api/public/buyer-consent/**").permitAll()

                // Visit requests (unauthenticated visitors can request)
                .requestMatchers(HttpMethod.POST, "/api/visitas").permitAll()
                .requestMatchers("/api/visitas/email-verification/**").permitAll()

                // WebSocket endpoint (auth handled in handshake interceptor)
                .requestMatchers("/ws/**").permitAll()

                // Webhooks (verified by signature, not session)
                .requestMatchers("/api/webhooks/**").permitAll()

                // Commute (public — used on listing detail)
                .requestMatchers(HttpMethod.POST, "/api/commute/**").permitAll()

                // Team invitations (token-based)
                .requestMatchers(HttpMethod.GET, "/api/convite/**").permitAll()

                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // JWT filter runs before UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            // Delegate 401/403 to our handler (returns JSON, not login redirect)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("""
                        {"error":{"code":"UNAUTHORIZED","message":"Sessão ausente ou expirada."}}
                        """);
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("""
                        {"error":{"code":"FORBIDDEN","message":"Acesso não autorizado."}}
                        """);
                })
            )
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:3000",    // Next.js dev
            "http://localhost:80",
            "https://*.properia.pt",
            "https://properia.pt",
            "https://*.pages.dev"       // CF Pages preview deployments
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);  // Required for cookie-based auth
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }
}
