package pt.properia.api.modules.team.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.team.application.TeamPermissionGuard;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/team/invites")
public class TeamInvitePublicController {

    private final JdbcClient jdbc;
    private final TeamPermissionGuard permissionGuard;

    public TeamInvitePublicController(JdbcClient jdbc, TeamPermissionGuard permissionGuard) {
        this.jdbc = jdbc;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> getInvite(@PathVariable String token) {
        var invite = jdbc.sql("""
                SELECT i.id, i.advertiser_id, i.email, i.membership_role,
                       i.accepted_at, i.expires_at, a.brand_name, a.logo_url,
                       u.full_name AS invited_by_name
                FROM properia.advertiser_team_invites i
                JOIN properia.advertisers a ON a.id = i.advertiser_id
                JOIN properia.app_users u ON u.id = i.invited_by_user_id
                WHERE i.token = :token
                """)
            .param("token", token)
            .query((rs, n) -> {
                var expiresAt = rs.getTimestamp("expires_at");
                var accepted = rs.getTimestamp("accepted_at");
                var isExpired = expiresAt != null && expiresAt.toInstant().isBefore(Instant.now());
                var status = accepted != null ? "accepted" : isExpired ? "expired" : "pending";

                var row = new HashMap<String, Object>();
                row.put("id", rs.getString("id"));
                row.put("advertiserId", rs.getString("advertiser_id"));
                row.put("email", rs.getString("email"));
                row.put("membershipRole", rs.getString("membership_role"));
                row.put("status", status);
                row.put("expiresAt", expiresAt != null ? expiresAt.toInstant().toString() : null);
                row.put("advertiserName", Optional.ofNullable(rs.getString("brand_name")).orElse("Anunciante"));
                row.put("advertiserLogoUrl", rs.getString("logo_url"));
                row.put("invitedByName", Optional.ofNullable(rs.getString("invited_by_name")).orElse("Um colega"));
                return row;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Convite não encontrado ou inválido.", 404));
        return ResponseEntity.ok(Map.of("data", invite));
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<?> acceptInvite(@PathVariable String token,
                                          @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Precisas de entrar para aceitar o convite.", 401);
        }
        var invite = jdbc.sql("""
                SELECT id, advertiser_id, email, membership_role, accepted_at, expires_at
                FROM properia.advertiser_team_invites
                WHERE token = :token
                """)
            .param("token", token)
            .query((rs, n) -> {
                var row = new HashMap<String, Object>();
                row.put("id", rs.getString("id"));
                row.put("advertiserId", rs.getString("advertiser_id"));
                row.put("email", rs.getString("email"));
                row.put("membershipRole", rs.getString("membership_role"));
                row.put("acceptedAt", rs.getTimestamp("accepted_at"));
                row.put("expiresAt", rs.getTimestamp("expires_at"));
                return row;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Convite não encontrado ou inválido.", 404));

        if (invite.get("acceptedAt") != null) {
            throw new DomainException("CONFLICT", "Este convite já foi aceite.", 409);
        }

        var expiresAt = invite.get("expiresAt");
        if (expiresAt instanceof java.sql.Timestamp ts && ts.toInstant().isBefore(Instant.now())) {
            throw new DomainException("FORBIDDEN", "Este convite expirou.", 410);
        }

        if (!claims.email().equalsIgnoreCase((String) invite.get("email"))) {
            throw new DomainException("FORBIDDEN",
                "Este convite foi enviado para " + invite.get("email") + ". Estás autenticado com " + claims.email() + ".", 403);
        }

        var advertiserId = UUID.fromString((String) invite.get("advertiserId"));
        var inviteId = UUID.fromString((String) invite.get("id"));

        // Check if already a member
        var existing = jdbc.sql("""
                SELECT 1 FROM properia.advertiser_users WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", claims.userId())
            .query((rs, n) -> 1).optional().isPresent();
        if (existing) {
            throw new DomainException("CONFLICT", "Já fazes parte desta equipa.", 409);
        }

        // Revalida o tecto de vagas no momento da aceitação, não só na criação do
        // convite: se o plano baixou de escalão enquanto este convite esteve
        // pendente, aceitá-lo agora não pode empurrar a equipa para além do tecto
        // do plano actual.
        permissionGuard.requireSeatAvailable(advertiserId);

        // Create membership and mark invite accepted
        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role, created_at)
                VALUES (:adv, :uid, :role::properia.advertiser_membership_role, now())
                ON CONFLICT (advertiser_id, user_id) DO NOTHING
                """)
            .param("adv", advertiserId)
            .param("uid", claims.userId())
            .param("role", invite.get("membershipRole"))
            .update();

        jdbc.sql("""
                UPDATE properia.advertiser_team_invites
                SET accepted_at = now(), accepted_by_user_id = :uid, updated_at = now()
                WHERE id = :id
                """)
            .param("uid", claims.userId())
            .param("id", inviteId)
            .update();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "advertiserId", advertiserId.toString(),
            "membershipRole", invite.get("membershipRole")
        )));
    }
}
