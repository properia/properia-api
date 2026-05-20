package pt.properia.api.shared.infrastructure.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.core.context.SecurityContextHolder;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA auditing: automatically populates @CreatedBy / @LastModifiedBy fields.
 * The auditor is the authenticated user's UUID from the JWT.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableJpaRepositories(basePackages = "pt.properia.api")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<UUID> auditorProvider() {
        return () -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return Optional.empty();
            }
            if (auth.getPrincipal() instanceof JwtClaims claims) {
                return Optional.of(claims.userId());
            }
            return Optional.empty();
        };
    }
}
