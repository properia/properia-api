package pt.properia.api.modules.auth.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
        if (this.enabled) {
            log.info("Email service enabled: from={} appUrl={}", from, appUrl);
        } else {
            log.warn("Email service DISABLED — RESEND_API_KEY not set. Emails will not be sent.");
        }
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

    public void sendVisitEmailVerificationCode(String to, String code) {
        send(to,
            "O teu código de verificação Properia",
            html("O teu código de verificação",
                "Usa o código abaixo para verificares o teu email e agendares visitas na Properia.<br><br>" +
                "<span style=\"font-size:32px;font-weight:700;letter-spacing:8px;color:#1a1a1a\">" + code + "</span><br><br>" +
                "O código expira em 10 minutos.",
                "Abrir Properia", appUrl + "/visitas"),
            "O teu código de verificação na Properia: " + code + " (válido 10 minutos)"
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
            log.warn("Email NOT sent (RESEND_API_KEY not configured): to={} subject={}", to, subject);
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
                    resp.bodyToMono(String.class).flatMap(body -> {
                        log.error("Resend API error {} sending to={} subject={}: {}", resp.statusCode().value(), to, subject, body);
                        return Mono.error(new RuntimeException("Resend error " + resp.statusCode().value() + ": " + body));
                    })
                )
                .toBodilessEntity()
                .block(java.time.Duration.ofSeconds(10));
            log.info("Email sent: to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to={} subject={}: {}", to, subject, e.getMessage());
            throw new RuntimeException("Falha ao enviar email: " + e.getMessage(), e);
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
