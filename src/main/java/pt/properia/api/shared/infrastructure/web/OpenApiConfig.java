package pt.properia.api.shared.infrastructure.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 3 configuration.
 * Swagger UI available at /swagger-ui.html
 * JSON spec at /api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Properia API")
                .description("""
                    Backend REST API for the Properia real estate platform.

                    **Authentication:** JWT via httpOnly cookie (`properia_session`) or
                    `Authorization: Bearer <token>` header.

                    **Legal:** Properia is a technology platform, not a real estate broker.
                    """)
                .version("0.1.0")
                .contact(new Contact()
                    .name("Properia Engineering")
                    .email("dev@properia.pt"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://properia.pt"))
            )
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local development"),
                new Server().url("https://api.properia.pt").description("Production")
            ))
            .components(new Components()
                .addSecuritySchemes("cookieAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.COOKIE)
                    .name("properia_session")
                    .description("httpOnly session cookie")
                )
                .addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Bearer token (for API clients and tests)")
                )
            )
            .addSecurityItem(new SecurityRequirement()
                .addList("cookieAuth")
                .addList("bearerAuth")
            );
    }
}
