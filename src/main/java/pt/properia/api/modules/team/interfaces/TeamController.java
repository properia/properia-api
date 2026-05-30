package pt.properia.api.modules.team.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.team.application.TeamService;
import pt.properia.api.modules.team.interfaces.request.CreateInviteRequest;
import pt.properia.api.modules.team.interfaces.request.UpdateMemberRoleRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/team")
public class TeamController {

    private final TeamService teamService;
    private final JdbcClient jdbc;

    public TeamController(TeamService teamService, JdbcClient jdbc) {
        this.teamService = teamService;
        this.jdbc = jdbc;
    }

    // ── Plan-aware team limit check ───────────────────────────────────────────

    private void assertTeamSlotAvailable(UUID advertiserId) {
        // Resolve effective plan (respects active trial)
        var row = jdbc.sql("""
                SELECT plan_code,
                       billing_metadata->>'trialActivatedAt' AS trial_activated_at,
                       billing_metadata->>'trialEndsAt'      AS trial_ends_at
                FROM properia.advertisers WHERE id = :id
                """).param("id", advertiserId)
            .query((rs, n) -> new String[]{
                rs.getString("plan_code"),
                rs.getString("trial_activated_at"),
                rs.getString("trial_ends_at")
            }).optional().orElse(new String[]{"starter", null, null});

        String planCode = row[0] != null ? row[0] : "starter";

        // Override with trial plan if still active
        if (row[1] != null && row[2] != null) {
            try {
                var endsAt = java.time.Instant.parse(row[2]);
                if (java.time.Instant.now().isBefore(endsAt)) {
                    planCode = "business";
                }
            } catch (Exception ignored) {}
        }

        int maxMembers = switch (planCode) {
            case "business", "pilot" -> -1;
            case "pro" -> 5;
            default -> 1; // starter / free
        };

        if (maxMembers == -1) return; // unlimited

        long current = jdbc.sql("""
                SELECT COUNT(*) FROM properia.advertiser_users WHERE advertiser_id = :adv
                """).param("adv", advertiserId).query(Long.class).single();

        if (current >= maxMembers) {
            String planLabel = "pro".equals(planCode) ? "Pro" : "Starter";
            throw new DomainException("PLAN_LIMIT_EXCEEDED",
                "O plano " + planLabel + " permite no máximo " + maxMembers +
                " membro(s) de equipa. Faz upgrade para adicionar mais.", 403);
        }
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @PostMapping("/members/direct")
    public ResponseEntity<?> addMemberDirect(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        assertTeamSlotAvailable(advertiserId);
        teamService.addMemberByEmail(advertiserId, claims.userId(), body.get("email"), body.get("membershipRole"));
        return ResponseEntity.status(201).body(Map.of("data", Map.of("added", true)));
    }

    @GetMapping("/members")
    public ResponseEntity<?> listMembers(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var members = teamService.listMembers(advertiserId, claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of(
            "members", members,
            "totalCount", members.size()
        )));
    }

    @PatchMapping("/members/{userId}")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable UUID userId,
            @RequestBody UpdateMemberRoleRequest body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        teamService.updateMemberRole(advertiserId, userId, claims.userId(), body.membershipRole());
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable UUID userId,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        teamService.removeMember(advertiserId, userId, claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("removed", true)));
    }

    // ── Invites ───────────────────────────────────────────────────────────────

    @GetMapping("/invites")
    public ResponseEntity<?> listInvites(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var invites = teamService.listInvites(advertiserId);
        return ResponseEntity.ok(Map.of("data", Map.of("invites", invites)));
    }

    @PostMapping("/invites")
    public ResponseEntity<?> createInvite(
            @RequestBody CreateInviteRequest body,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        assertTeamSlotAvailable(advertiserId);
        var invite = teamService.createInvite(advertiserId, claims.userId(), body.email(), body.membershipRole());
        return ResponseEntity.status(201).body(Map.of("data", Map.of("invite", invite)));
    }

    @DeleteMapping("/invites/{id}")
    public ResponseEntity<?> cancelInvite(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        teamService.cancelInvite(advertiserId, id);
        return ResponseEntity.ok(Map.of("data", Map.of("cancelled", true)));
    }

    @PostMapping("/invites/{id}/resend")
    public ResponseEntity<?> resendInvite(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var invite = teamService.resendInvite(advertiserId, id, claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("invite", invite)));
    }

    // ── Invite acceptance (public — token flow) ───────────────────────────────

    @PostMapping("/invites/accept")
    public ResponseEntity<?> acceptInvite(
            @RequestParam String token,
            @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "É necessário autenticação para aceitar o convite.", 401);
        }
        teamService.acceptInvite(token, claims.userId());
        return ResponseEntity.ok(Map.of("data", Map.of("accepted", true)));
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Acesso negado.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
