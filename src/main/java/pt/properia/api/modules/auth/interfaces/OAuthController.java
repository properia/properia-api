package pt.properia.api.modules.auth.interfaces;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import pt.properia.api.modules.auth.application.AuthRepository;
import pt.properia.api.modules.auth.application.dto.SessionUserDto;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtProperties;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    private static final String SESSION_COOKIE = "properia_session";
    private static final String STATE_COOKIE = "properia_oauth_state";
    private static final String PKCE_COOKIE = "properia_oauth_pkce";
    private static final String NEXT_COOKIE = "properia_oauth_next";

    private final AuthRepository authRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;

    @Value("${properia.oauth.issuer-url:https://accounts.google.com}")
    private String issuerUrl;
    @Value("${properia.oauth.client-id:}")
    private String clientId;
    @Value("${properia.oauth.client-secret:}")
    private String clientSecret;
    @Value("${properia.oauth.redirect-uri:http://localhost:8080/api/auth/callback}")
    private String redirectUri;
    @Value("${properia.app.url:http://localhost:3000}")
    private String appUrl;

    private final SecureRandom rng = new SecureRandom();
    private final WebClient http = WebClient.create();

    // Cache discovered endpoints (not critical for correctness)
    private volatile String authorizationEndpoint;
    private volatile String tokenEndpoint;
    private volatile String userinfoEndpoint;

    public OAuthController(AuthRepository authRepository, JwtService jwtService, JwtProperties jwtProps) {
        this.authRepository = authRepository;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
    }

    @GetMapping("/google/login")
    public void googleLogin(@RequestParam(defaultValue = "/") String next,
                             HttpServletRequest request,
                             HttpServletResponse response) throws Exception {
        if (clientId.isBlank()) {
            response.sendRedirect(appUrl + "/login?error=oauth_unavailable");
            return;
        }

        ensureDiscovery();

        String state = randomBase64(24);
        String verifier = randomBase64(64);
        String challenge = pkceChallenge(verifier);
        String safePath = next.startsWith("/") && !next.startsWith("//") ? next : "/";

        // Store OAuth state in short-lived cookies
        setOAuthCookie(response, STATE_COOKIE, state, 600);
        setOAuthCookie(response, PKCE_COOKIE, verifier, 600);
        setOAuthCookie(response, NEXT_COOKIE, safePath, 600);

        String authUrl = authorizationEndpoint
            + "?response_type=code"
            + "&client_id=" + enc(clientId)
            + "&redirect_uri=" + enc(redirectUri)
            + "&scope=" + enc("openid email profile")
            + "&state=" + enc(state)
            + "&code_challenge=" + enc(challenge)
            + "&code_challenge_method=S256";

        response.sendRedirect(authUrl);
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                          @RequestParam(required = false) String state,
                          @RequestParam(required = false) String error,
                          HttpServletRequest request,
                          HttpServletResponse response) throws Exception {
        if (error != null || code == null) {
            clearOAuthCookies(response);
            String errCode = error != null ? enc(error) : "missing_code";
            String dbg = "&dbg_code_null=" + (code == null) + "&dbg_error=" + (error != null ? enc(error) : "none");
            response.sendRedirect(appUrl + "/login?error=" + errCode + dbg);
            return;
        }

        String storedState = cookieValue(request, STATE_COOKIE);
        String verifier = cookieValue(request, PKCE_COOKIE);
        String nextPath = cookieValue(request, NEXT_COOKIE);

        if (storedState == null || !storedState.equals(state) || verifier == null) {
            clearOAuthCookies(response);
            String dbg = "&dbg_stored_null=" + (storedState == null) + "&dbg_verifier_null=" + (verifier == null) + "&dbg_state_match=" + (storedState != null && storedState.equals(state));
            response.sendRedirect(appUrl + "/login?error=state_mismatch" + dbg);
            return;
        }

        clearOAuthCookies(response);

        try {
            ensureDiscovery();
            var userInfo = exchangeCodeForUserInfo(code, verifier);
            String email = (String) userInfo.get("email");
            String sub = (String) userInfo.get("sub");
            String name = (String) userInfo.getOrDefault("name", email);
            String picture = (String) userInfo.get("picture");
            boolean emailVerified = Boolean.TRUE.equals(userInfo.get("email_verified"));

            var user = authRepository.resolveOAuthIdentity(new AuthRepository.ResolveOAuthIdentityInput(
                "google", sub, email, name, picture, emailVerified
            ));

            SessionUserDto session = authRepository.buildSessionUser(user.id());
            String token = buildToken(session);
            String dest = nextPath != null && nextPath.startsWith("/") ? nextPath : "/";
            response.sendRedirect(appUrl + "/api/auth/exchange?token=" + enc(token) + "&next=" + enc(dest));
        } catch (Exception e) {
            log.error("OAuth callback failed: {}", e.getMessage(), e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String safe = detail.length() > 200 ? detail.substring(0, 200) : detail;
            response.sendRedirect(appUrl + "/login?error=callback_failed&detail=" + enc(safe));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ensureDiscovery() throws Exception {
        if (authorizationEndpoint != null) return;
        synchronized (this) {
            if (authorizationEndpoint != null) return;
            String discoveryUrl = issuerUrl.stripTrailing() + "/.well-known/openid-configuration";
            var discovery = http.get().uri(discoveryUrl).retrieve()
                .bodyToMono(Map.class).block();
            if (discovery == null) throw new RuntimeException("OIDC discovery failed");
            authorizationEndpoint = (String) discovery.get("authorization_endpoint");
            tokenEndpoint = (String) discovery.get("token_endpoint");
            userinfoEndpoint = (String) discovery.get("userinfo_endpoint");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForUserInfo(String code, String verifier) {
        var tokenResponse = http.post().uri(tokenEndpoint)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .bodyValue("grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&code_verifier=" + enc(verifier))
            .retrieve()
            .bodyToMono(Map.class).block();

        if (tokenResponse == null) throw new RuntimeException("Token exchange failed");
        String accessToken = (String) tokenResponse.get("access_token");

        var userInfo = http.get().uri(userinfoEndpoint)
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(Map.class).block();

        if (userInfo == null) throw new RuntimeException("UserInfo fetch failed");
        return userInfo;
    }

    private String buildToken(SessionUserDto session) {
        return jwtService.generateToken(new JwtClaims(
            session.sub(), session.email(), session.name(), session.role(),
            session.avatarUrl(), session.hasAdvertiserAccess(),
            session.activeAdvertiserId(), UUID.randomUUID()
        ));
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie",
            SESSION_COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Lax"
                + (jwtProps.isCookieSecure() ? "; Secure" : "")
                + "; Max-Age=" + jwtProps.getTtlSeconds()
                + (jwtProps.getCookieDomain() != null && !jwtProps.getCookieDomain().isBlank()
                    ? "; Domain=" + jwtProps.getCookieDomain() : "")
        );
    }

    private void setOAuthCookie(HttpServletResponse response, String name, String value, int maxAge) {
        response.addHeader("Set-Cookie",
            name + "=" + value
                + "; Path=/; HttpOnly; SameSite=Lax"
                + (jwtProps.isCookieSecure() ? "; Secure" : "")
                + "; Max-Age=" + maxAge
        );
    }

    private void clearOAuthCookies(HttpServletResponse response) {
        for (String name : List.of(STATE_COOKIE, PKCE_COOKIE, NEXT_COOKIE)) {
            response.addHeader("Set-Cookie", name + "=; Path=/; Max-Age=0; SameSite=Lax");
        }
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(c -> name.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst().orElse(null);
    }

    private String randomBase64(int byteLen) {
        byte[] b = new byte[byteLen];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String pkceChallenge(String verifier) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
