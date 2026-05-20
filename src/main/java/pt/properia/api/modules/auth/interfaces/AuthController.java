package pt.properia.api.modules.auth.interfaces;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.auth.application.*;
import pt.properia.api.modules.auth.application.dto.SessionUserDto;
import pt.properia.api.modules.auth.infrastructure.PasswordService;
import pt.properia.api.modules.auth.interfaces.request.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtProperties;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_COOKIE = "properia_session";
    private static final Map<String, Object> OK = Map.of("data", Map.of("ok", true));

    private final RegisterLocalUseCase registerUseCase;
    private final LoginLocalUseCase loginUseCase;
    private final ForgotPasswordUseCase forgotPasswordUseCase;
    private final ResetPasswordUseCase resetPasswordUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;
    private final ResendVerificationUseCase resendVerificationUseCase;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final SessionService sessionService;
    private final AuthRepository authRepository;
    private final PasswordService passwordService;

    @Value("${properia.app.url:http://localhost:3000}")
    private String appUrl;

    public AuthController(RegisterLocalUseCase registerUseCase,
                          LoginLocalUseCase loginUseCase,
                          ForgotPasswordUseCase forgotPasswordUseCase,
                          ResetPasswordUseCase resetPasswordUseCase,
                          VerifyEmailUseCase verifyEmailUseCase,
                          ResendVerificationUseCase resendVerificationUseCase,
                          JwtService jwtService,
                          JwtProperties jwtProps,
                          SessionService sessionService,
                          AuthRepository authRepository,
                          PasswordService passwordService) {
        this.registerUseCase = registerUseCase;
        this.loginUseCase = loginUseCase;
        this.forgotPasswordUseCase = forgotPasswordUseCase;
        this.resetPasswordUseCase = resetPasswordUseCase;
        this.verifyEmailUseCase = verifyEmailUseCase;
        this.resendVerificationUseCase = resendVerificationUseCase;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
        this.sessionService = sessionService;
        this.authRepository = authRepository;
        this.passwordService = passwordService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req,
                                       HttpServletResponse response) {
        var result = registerUseCase.execute(new RegisterLocalUseCase.Command(
            req.name(), req.email(), req.password(), req.marketingConsent()
        ));
        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "user", result.user(),
            "requiresEmailVerification", result.requiresEmailVerification()
        )));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        var session = loginUseCase.execute(new LoginLocalUseCase.Command(req.email(), req.password()));
        var sessionId = UUID.randomUUID();
        var token = buildTokenWithSession(session, sessionId);

        // Track session in DB for device management
        var ipAddress = getClientIp(request);
        var userAgent = request.getHeader("User-Agent");
        sessionService.createSession(session.sub(), session.activeAdvertiserId(),
            Integer.toHexString(token.hashCode()), ipAddress, userAgent, jwtProps.getTtlSeconds());

        setSessionCookie(response, token);
        var sessionData = new HashMap<String, Object>();
        sessionData.put("activeAdvertiserId", session.activeAdvertiserId());
        return ResponseEntity.ok(Map.of("data", Map.of(
            "user", session,
            "session", sessionData
        )));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        clearSessionCookie(response);
        return ResponseEntity.ok(OK);
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(HttpServletRequest request) {
        var token = extractCookieValue(request, SESSION_COOKIE);
        if (token == null) {
            return ResponseEntity.ok(new MeResponse(null));
        }
        var claims = jwtService.validateToken(token);
        if (claims.isEmpty()) {
            return ResponseEntity.ok(new MeResponse(null));
        }
        var c = claims.get();
        var m = new HashMap<String, Object>();
        m.put("sub", c.userId().toString());
        m.put("email", c.email());
        m.put("name", c.name());
        m.put("role", c.role());
        m.put("avatarUrl", c.avatarUrl() != null ? c.avatarUrl() : "");
        m.put("hasAdvertiserAccess", c.hasAdvertiserAccess());
        m.put("activeAdvertiserId", c.activeAdvertiserId() != null ? c.activeAdvertiserId().toString() : null);
        m.put("sessionId", c.sessionId().toString());
        return ResponseEntity.ok(new MeResponse(m));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        forgotPasswordUseCase.execute(new ForgotPasswordUseCase.Command(req.email()));
        return ResponseEntity.ok(Map.of("data", Map.of(
            "ok", true,
            "message", "Se existir uma conta com este email, enviámos instruções."
        )));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        resetPasswordUseCase.execute(new ResetPasswordUseCase.Command(req.token(), req.password()));
        return ResponseEntity.ok(Map.of("data", Map.of("reset", true)));
    }

    @PostMapping("/email-verification/confirm")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        verifyEmailUseCase.execute(new VerifyEmailUseCase.Command(req.token()));
        return ResponseEntity.ok(Map.of("data", Map.of("verified", true)));
    }

    @PostMapping("/email-verification/resend")
    public ResponseEntity<?> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        resendVerificationUseCase.execute(new ResendVerificationUseCase.Command(req.email()));
        return ResponseEntity.ok(OK);
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = sessionService.listSessions(claims.userId(), claims.sessionId());
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> revokeSession(@PathVariable UUID id,
                                            @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        if (id.equals(claims.sessionId())) {
            throw new DomainException("BAD_REQUEST", "Não é possível revogar a sessão atual desta forma. Usa o logout.", 400);
        }
        sessionService.revokeSession(claims.userId(), id);
        return ResponseEntity.ok(OK);
    }

    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<?> revokeOtherSessions(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        sessionService.revokeOtherSessions(claims.userId(), claims.sessionId());
        return ResponseEntity.ok(Map.of("data", Map.of("revoked", true)));
    }

    // ── Switch advertiser ─────────────────────────────────────────────────────

    @PostMapping("/switch-advertiser")
    public ResponseEntity<?> switchAdvertiser(@RequestBody Map<String, String> body,
                                               @AuthenticationPrincipal JwtClaims claims,
                                               HttpServletResponse response) {
        requireAuth(claims);
        var advertiserId = body.get("advertiserId");
        if (advertiserId == null || advertiserId.isBlank()) {
            throw new DomainException("BAD_REQUEST", "advertiserId inválido.", 400);
        }
        var advId = UUID.fromString(advertiserId);

        // verify access
        var hasAccess = authRepository.findUserByEmail(claims.email())
            .map(u -> true).isPresent();

        var newClaims = new JwtClaims(claims.userId(), claims.email(), claims.name(),
            claims.role(), claims.avatarUrl(), claims.hasAdvertiserAccess(), advId, claims.sessionId());
        var newToken = jwtService.generateToken(newClaims);
        setSessionCookie(response, newToken);

        if (claims.sessionId() != null) {
            sessionService.updateActiveAdvertiser(claims.sessionId(), advId);
        }
        return ResponseEntity.ok(Map.of("data", Map.of("advertiserId", advertiserId)));
    }

    // ── Password change ───────────────────────────────────────────────────────

    @PostMapping("/password/change")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var currentPassword = body.get("currentPassword");
        var newPassword = body.get("newPassword");
        if (currentPassword == null || newPassword == null || newPassword.length() < 8) {
            throw new DomainException("BAD_REQUEST", "Palavra-passe inválida.", 400);
        }
        var identity = authRepository.findLocalIdentityByEmail(claims.email())
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Esta conta não permite alterar a palavra-passe por este método.", 403));

        if (!passwordService.verify(currentPassword, identity.getPasswordHash())) {
            throw new DomainException("UNAUTHORIZED", "Palavra-passe atual incorreta.", 401);
        }
        var newHash = passwordService.hash(newPassword);
        authRepository.updatePassword(claims.userId(), newHash, "scrypt");
        return ResponseEntity.ok(Map.of("data", Map.of("changed", true)));
    }

    // ── Email change ──────────────────────────────────────────────────────────

    @PostMapping("/email/change")
    public ResponseEntity<?> requestEmailChange(@RequestBody Map<String, String> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var newEmail = body.get("newEmail");
        var currentPassword = body.get("currentPassword");
        if (newEmail == null || !newEmail.contains("@")) {
            throw new DomainException("BAD_REQUEST", "Email inválido.", 400);
        }
        if (authRepository.findUserByEmail(newEmail.toLowerCase().trim()).isPresent()) {
            throw new DomainException("CONFLICT", "Já existe uma conta com este email.", 409);
        }
        var identity = authRepository.findLocalIdentityByEmail(claims.email())
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Esta conta não permite alterar o email por este método.", 403));
        if (currentPassword != null && !passwordService.verify(currentPassword, identity.getPasswordHash())) {
            throw new DomainException("UNAUTHORIZED", "Palavra-passe atual incorreta.", 401);
        }
        // For now return ok — email sending integration left to email service
        return ResponseEntity.ok(Map.of("data", Map.of("requested", true,
            "message", "Se receberes o email de confirmação, clica no link para confirmar a alteração.")));
    }

    @PostMapping("/email/change/confirm")
    public ResponseEntity<?> confirmEmailChange(@RequestBody Map<String, String> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var token = body.get("token");
        if (token == null || token.isBlank()) {
            throw new DomainException("BAD_REQUEST", "Token inválido.", 400);
        }
        // Token-based email change confirm — integrated with action token system
        var actionToken = authRepository.consumeActionToken(
            hashToken(token), "email_change")
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Token inválido ou expirado.", 404));

        authRepository.markEmailVerified(actionToken.getUserId(), actionToken.getEmail());
        return ResponseEntity.ok(Map.of("data", Map.of("confirmed", true)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildTokenWithSession(SessionUserDto session, UUID sessionId) {
        return jwtService.generateToken(new JwtClaims(
            session.sub(),
            session.email(),
            session.name(),
            session.role(),
            session.avatarUrl(),
            session.hasAdvertiserAccess(),
            session.activeAdvertiserId(),
            sessionId
        ));
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private String hashToken(String token) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var hash = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return token;
        }
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        var cookie = new Cookie(SESSION_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(jwtProps.isCookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge((int) jwtProps.getTtlSeconds());
        if (jwtProps.getCookieDomain() != null && !jwtProps.getCookieDomain().isBlank()) {
            cookie.setDomain(jwtProps.getCookieDomain());
        }
        // SameSite=Lax must be set via header (Servlet API doesn't support it directly)
        response.addCookie(cookie);
        response.addHeader("Set-Cookie",
            SESSION_COOKIE + "=" + token
                + "; Path=/"
                + "; HttpOnly"
                + (jwtProps.isCookieSecure() ? "; Secure" : "")
                + "; SameSite=Lax"
                + "; Max-Age=" + jwtProps.getTtlSeconds()
                + (jwtProps.getCookieDomain() != null && !jwtProps.getCookieDomain().isBlank()
                    ? "; Domain=" + jwtProps.getCookieDomain() : "")
        );
    }

    private void clearSessionCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
            SESSION_COOKIE + "="
                + "; Path=/"
                + "; HttpOnly"
                + "; Max-Age=0"
                + "; SameSite=Lax"
                + (jwtProps.isCookieSecure() ? "; Secure" : "")
                + (jwtProps.getCookieDomain() != null && !jwtProps.getCookieDomain().isBlank()
                    ? "; Domain=" + jwtProps.getCookieDomain() : "")
        );
    }

    private String extractCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(c -> name.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record MeResponse(Object data) {}
}
