package pt.properia.api.modules.auth.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import pt.properia.api.shared.domain.DomainException;
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

    public void sendVisitConfirmationRequest(String to, String listingTitle, String whenLabel) {
        String url = appUrl + "/visitas";
        String when = (whenLabel != null && !whenLabel.isBlank()) ? " (" + whenLabel + ")" : "";
        send(to,
            "Confirma a tua visita na Properia",
            html("Confirma a tua visita",
                "O anunciante pediu para confirmares a tua presença na visita a <strong>" + listingTitle + "</strong>" + when + ".<br><br>" +
                "Confirma para garantires o teu lugar. Se já não puderes ir, avisa para libertar a vaga para outra pessoa.",
                "Confirmar presença", url),
            "Confirma a tua visita a " + listingTitle + when + ": " + url
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

    public void sendTeamInvite(String to, String inviterName, String agencyName, String role, String token) {
        String acceptUrl = appUrl + "/convite/" + token;
        String roleLabel = switch (role) {
            case "admin"  -> "Administrador";
            case "editor" -> "Editor";
            case "sales"  -> "Corretor";
            case "viewer" -> "Visualizador";
            default       -> role;
        };
        send(to,
            inviterName + " convidou-te para a equipa na Properia",
            teamInviteHtml(inviterName, agencyName, roleLabel, acceptUrl),
            inviterName + " convidou-te para a equipa \"" + agencyName + "\" na Properia como " + roleLabel + ".\nAceita aqui: " + acceptUrl + "\nO convite expira em 7 dias."
        );
    }

    public void sendSignatureRequest(String to, String signerName, String docTitle, String otp, String token) {
        String url = appUrl + "/assinar/" + token;
        String greeting = (signerName != null && !signerName.isBlank()) ? "Olá " + signerName + "," : "Olá,";
        send(to,
            "Documento para assinar: " + docTitle,
            html("Documento para assinar",
                greeting + "<br><br>Foi-lhe enviado o documento <strong>" + docTitle + "</strong> para assinatura eletrónica.<br><br>" +
                "Ao abrir o link, vai precisar deste código de verificação:<br><br>" +
                "<span style=\"font-size:32px;font-weight:700;letter-spacing:8px;color:#1a1a1a\">" + otp + "</span><br><br>" +
                "O código expira em 15 minutos.",
                "Rever e assinar", url),
            greeting + "\nDocumento para assinar: " + docTitle + "\nCódigo de verificação: " + otp + " (válido 15 minutos)\nAssine aqui: " + url
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
                        log.error("Resend API error status={} from='{}' to={} subject='{}' body={}",
                            resp.statusCode().value(), from, to, subject, body);
                        return Mono.error(new DomainException("EMAIL_SEND_FAILED",
                            "Resend " + resp.statusCode().value() + ": " + body, 503));
                    })
                )
                .toBodilessEntity()
                .block(java.time.Duration.ofSeconds(10));
            log.info("Email sent ok: from='{}' to={} subject='{}'", from, to, subject);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send email from='{}' to={} subject='{}': {}", from, to, subject, e.getMessage());
            throw new DomainException("EMAIL_SEND_FAILED", "Não foi possível enviar o email: " + e.getMessage(), 503);
        }
    }

    private String teamInviteHtml(String inviterName, String agencyName, String roleLabel, String acceptUrl) {
        return """
            <!DOCTYPE html>
            <html lang="pt">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#f4f4f5;font-family:Arial,Helvetica,sans-serif">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5;padding:40px 16px">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:20px;overflow:hidden;box-shadow:0 2px 16px rgba(0,0,0,.08)">
                    <!-- header -->
                    <tr>
                      <td style="padding:32px 40px 24px;border-bottom:1px solid #f0f0f0" align="center">
                        <img src="https://media.properia.pt/brand/properia-logo-email.png"
                             alt="Properia" width="140" style="display:block;height:auto">
                      </td>
                    </tr>
                    <!-- body -->
                    <tr>
                      <td style="padding:36px 40px 8px">
                        <p style="margin:0 0 6px;font-size:11px;font-weight:700;letter-spacing:2px;color:#c4622d;text-transform:uppercase">Convite para a equipa</p>
                        <h1 style="margin:0 0 20px;font-size:24px;font-weight:800;color:#111;line-height:1.25">
                          Junta-te à equipa<br>da %s
                        </h1>
                        <p style="margin:0 0 24px;font-size:15px;color:#444;line-height:1.6">
                          <strong>%s</strong> convidou-te para fazer parte da equipa <strong>%s</strong> na Properia.
                        </p>
                        <!-- role pill -->
                        <p style="margin:0 0 28px">
                          <span style="display:inline-block;background:#f6ece5;color:#8a4a2b;font-size:13px;font-weight:700;padding:6px 14px;border-radius:20px">%s</span>
                        </p>
                        <!-- CTA button -->
                        <table cellpadding="0" cellspacing="0" style="margin-bottom:24px">
                          <tr>
                            <td style="border-radius:10px;background:linear-gradient(135deg,#c4622d,#e8855a)">
                              <a href="%s"
                                 style="display:inline-block;padding:14px 32px;font-size:15px;font-weight:700;color:#fff;text-decoration:none;border-radius:10px;background:linear-gradient(135deg,#c4622d,#e8855a)">
                                Aceitar convite →
                              </a>
                            </td>
                          </tr>
                        </table>
                        <p style="margin:0 0 8px;font-size:13px;color:#888">
                          Ou copia este link para o navegador:<br>
                          <a href="%s" style="color:#c4622d;word-break:break-all">%s</a>
                        </p>
                      </td>
                    </tr>
                    <!-- expiry notice -->
                    <tr>
                      <td style="padding:20px 40px;background:#fafafa;border-top:1px solid #f0f0f0">
                        <p style="margin:0;font-size:12px;color:#aaa">Este convite expira em 7 dias. Se não reconheces este email, podes ignorá-lo com segurança.</p>
                      </td>
                    </tr>
                    <!-- footer -->
                    <tr>
                      <td style="padding:20px 40px" align="center">
                        <p style="margin:0;font-size:12px;color:#ccc">Properia · Plataforma tecnológica imobiliária · properia.pt</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(agencyName, inviterName, agencyName, roleLabel, acceptUrl, acceptUrl, acceptUrl);
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
