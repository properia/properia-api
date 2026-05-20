package pt.properia.api.modules.team.interfaces;

import org.springframework.http.ResponseEntity;
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

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    // ── Members ───────────────────────────────────────────────────────────────

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
