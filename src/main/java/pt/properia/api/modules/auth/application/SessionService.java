package pt.properia.api.modules.auth.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class SessionService {

    private final JdbcClient jdbc;

    public SessionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionSummary(UUID id, String label, String ipAddress,
                                 Instant createdAt, Instant lastSeenAt,
                                 Instant expiresAt, Instant revokedAt, boolean isCurrent) {}

    public UUID createSession(UUID userId, UUID advertiserId, String tokenHash,
                              String ipAddress, String userAgent, long ttlSeconds) {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var expiresAt = now.plusSeconds(ttlSeconds);

        jdbc.sql("""
                INSERT INTO properia.user_sessions
                  (id, user_id, session_token_hash, ip_address, user_agent,
                   active_advertiser_id, expires_at, last_seen_at, created_at, updated_at)
                VALUES (:id, :userId, :hash, :ip, :ua, :adv, :exp, :now, :now, :now)
                ON CONFLICT (session_token_hash) DO NOTHING
                """)
            .param("id", id)
            .param("userId", userId)
            .param("hash", tokenHash)
            .param("ip", ipAddress)
            .param("ua", userAgent != null ? userAgent.substring(0, Math.min(500, userAgent.length())) : null)
            .param("adv", advertiserId)
            .param("exp", expiresAt)
            .param("now", now)
            .update();
        return id;
    }

    public List<SessionSummary> listSessions(UUID userId, UUID currentSessionId) {
        return jdbc.sql("""
                SELECT id, user_agent, ip_address, created_at, last_seen_at, expires_at, revoked_at
                FROM properia.user_sessions
                WHERE user_id = :uid AND expires_at > now()
                ORDER BY last_seen_at DESC NULLS LAST
                """)
            .param("uid", userId)
            .query((rs, n) -> {
                var id = UUID.fromString(rs.getString("id"));
                var ua = rs.getString("user_agent");
                var label = ua != null ? ua.substring(0, Math.min(120, ua.length())) : "Dispositivo sem detalhe";
                var revokedAt = rs.getTimestamp("revoked_at");
                return new SessionSummary(
                    id, label,
                    rs.getString("ip_address"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("last_seen_at") != null ? rs.getTimestamp("last_seen_at").toInstant() : null,
                    rs.getTimestamp("expires_at").toInstant(),
                    revokedAt != null ? revokedAt.toInstant() : null,
                    id.equals(currentSessionId)
                );
            })
            .list();
    }

    public void revokeSession(UUID userId, UUID sessionId) {
        var updated = jdbc.sql("""
                UPDATE properia.user_sessions
                SET revoked_at = now(), updated_at = now()
                WHERE id = :id AND user_id = :uid AND revoked_at IS NULL
                """)
            .param("id", sessionId)
            .param("uid", userId)
            .update();
        if (updated == 0) throw new DomainException("NOT_FOUND", "Sessão não encontrada.", 404);
    }

    public void revokeOtherSessions(UUID userId, UUID keepSessionId) {
        jdbc.sql("""
                UPDATE properia.user_sessions
                SET revoked_at = now(), updated_at = now()
                WHERE user_id = :uid AND id != :keep AND revoked_at IS NULL
                """)
            .param("uid", userId)
            .param("keep", keepSessionId)
            .update();
    }

    public void updateActiveAdvertiser(UUID sessionId, UUID advertiserId) {
        jdbc.sql("""
                UPDATE properia.user_sessions
                SET active_advertiser_id = :adv, updated_at = now()
                WHERE id = :id
                """)
            .param("adv", advertiserId)
            .param("id", sessionId)
            .update();
    }

    public void touchLastSeen(UUID sessionId) {
        jdbc.sql("""
                UPDATE properia.user_sessions
                SET last_seen_at = now(), updated_at = now()
                WHERE id = :id
                """)
            .param("id", sessionId)
            .update();
    }
}
