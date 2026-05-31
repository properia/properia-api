package pt.properia.api.modules.chat.interfaces;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import pt.properia.api.shared.infrastructure.web.jwt.JwtProperties;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.util.Arrays;
import java.util.Map;

@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;
    private final JwtProperties props;

    public ChatWebSocketHandshakeInterceptor(JwtService jwtService, JwtProperties props) {
        this.jwtService = jwtService;
        this.props = props;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var cookies = servletRequest.getServletRequest().getCookies();
            if (cookies != null) {
                Arrays.stream(cookies)
                    .filter(c -> props.getCookieName().equals(c.getName()))
                    .map(c -> c.getValue())
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .flatMap(jwtService::validateToken)
                    .ifPresent(claims -> attributes.put("jwtClaims", claims));
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }
}
