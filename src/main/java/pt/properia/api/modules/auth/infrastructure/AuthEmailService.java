package pt.properia.api.modules.auth.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class AuthEmailService {

    private static final Logger log = LoggerFactory.getLogger(AuthEmailService.class);

    private final WebClient resendClient;
    private final String from;
    private final String appUrl;
    private final boolean enabled;

    public AuthEmailService(
        @Value("${properia.email.resend-api-key:}") String resendApiKey,
        @Value("${properia.email.from:Properia <noreply@properia.pt>}") String from,
        @Value("${properia.app.url:http://localhost:3000}") String appUrl
    ) {
        this.from = from;
        this.appUrl = appUrl;
        this.enabled = resendApiKey != null && !resendApiKey.isBlank();
        this.resendClient = WebClient.builder()
            .baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + resendApiKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    public void sendEmailVerification(String to, String token) {
        String url = appUrl + "/auth/verificar-email?token=" + token;
        send(to,
            "Confirma o teu email na Properia",
            html("Confirma o teu email",
                "Confirma o teu email para desbloqueares as ações principais da tua conta na Properia.",
                "Confirmar email", url),
            "Confirma o teu email na Properia: " + url
        );
    }

    public void sendPasswordReset(String to, String token) {
        String url = appUrl + "/repor-palavra-passe?token=" + token;
        send(to,
            "Repor palavra-passe da Properia",
            html("Repõe a tua palavra-passe",
                "Recebemos um pedido para repor a tua palavra-passe.",
                "Definir nova palavra-passe", url),
            "Repõe a tua palavra-passe da Properia: " + url
        );
    }

    private void send(String to, String subject, String htmlBody, String textBody) {
        if (!enabled) {
            log.info("Email not sent (RESEND_API_KEY not configured): to={} subject={}", to, subject);
            return;
        }
        try {
            resendClient.post()
                .uri("/emails")
                .bodyValue(Map.of(
                    "from", from,
                    "to", new String[]{to},
                    "subject", subject,
                    "html", htmlBody,
                    "text", textBody
                ))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), resp ->
                    resp.bodyToMono(String.class).doOnNext(body ->
                        log.error("Resend API error {}: {}", resp.statusCode().value(), body)
                    ).thenReturn(new RuntimeException("Resend error " + resp.statusCode().value()))
                )
                .toBodilessEntity()
                .subscribe(
                    ignored -> log.info("Email sent: to={} subject={}", to, subject),
                    err -> log.error("Failed to send email to={} subject={}: {}", to, subject, err.getMessage())
                );
        } catch (Exception e) {
            log.error("Email send error to={} subject={}: {}", to, subject, e.getMessage());
        }
    }

    private String html(String title, String body, String ctaLabel, String ctaUrl) {
        return """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto">
              <h2 style="color:#1a1a1a">%s</h2>
              <p style="color:#444">%s</p>
              <a href="%s" style="display:inline-block;background:#2563eb;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:600">%s</a>
              <p style="margin-top:24px;color:#888;font-size:12px">Properia · Plataforma tecnológica imobiliária</p>
            </div>
            """.formatted(title, body, ctaUrl, ctaLabel);
    }
}
