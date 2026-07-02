package pt.properia.api.modules.team.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.modules.team.domain.AdvertiserTeamInvite;
import pt.properia.api.modules.team.domain.AdvertiserUser;
import pt.properia.api.modules.team.domain.AdvertiserUserId;
import pt.properia.api.modules.team.infrastructure.AdvertiserTeamInviteJpaRepository;
import pt.properia.api.modules.team.infrastructure.AdvertiserUserJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional
public class TeamService {

    private static final Set<String> VALID_ROLES = Set.of("owner", "admin", "editor", "sales", "viewer");
    private static final int INVITE_TTL_DAYS = 7;

    private final AdvertiserUserJpaRepository memberRepo;
    private final AdvertiserTeamInviteJpaRepository inviteRepo;
    private final JdbcClient jdbc;
    private final AuthEmailService emailService;
    private final SecureRandom rng = new SecureRandom();

    public TeamService(AdvertiserUserJpaRepository memberRepo,
                       AdvertiserTeamInviteJpaRepository inviteRepo,
                       JdbcClient jdbc,
                       AuthEmailService emailService) {
        this.memberRepo = memberRepo;
        this.inviteRepo = inviteRepo;
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    // ── Member records with user info ─────────────────────────────────────────

    public record MemberDto(UUID userId, String name, String email, String avatarUrl,
                            String membershipRole, Instant joinedAt, boolean isCurrentUser) {}

    @Transactional(readOnly = true)
    public List<MemberDto> listMembers(UUID advertiserId, UUID currentUserId) {
        return jdbc.sql("""
                SELECT au.user_id, u.full_name, u.email, u.avatar_url,
                       au.membership_role, au.created_at
                FROM properia.advertiser_users au
                JOIN properia.app_users u ON u.id = au.user_id
                WHERE au.advertiser_id = :advertiserId
                ORDER BY au.created_at
                """)
            .param("advertiserId", advertiserId)
            .query((rs, n) -> new MemberDto(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("avatar_url"),
                rs.getString("membership_role"),
                rs.getTimestamp("created_at").toInstant(),
                UUID.fromString(rs.getString("user_id")).equals(currentUserId)
            ))
            .list();
    }

    public void updateMemberRole(UUID advertiserId, UUID userId, UUID requestorId, String newRole) {
        if (!VALID_ROLES.contains(newRole)) {
            throw new DomainException("BAD_REQUEST", "Role inválida.", 400);
        }
        var member = memberRepo.findByAdvertiserIdAndUserId(advertiserId, userId)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Membro não encontrado.", 404));

        if ("owner".equals(member.getMembershipRole()) && !userId.equals(requestorId)) {
            throw new DomainException("FORBIDDEN", "Não é possível alterar o role do owner.", 403);
        }
        if ("owner".equals(newRole)) {
            throw new DomainException("FORBIDDEN", "Use a transferência de ownership.", 403);
        }

        member.setMembershipRole(newRole);
        memberRepo.save(member);
    }

    public void removeMember(UUID advertiserId, UUID userId, UUID requestorId) {
        var member = memberRepo.findByAdvertiserIdAndUserId(advertiserId, userId)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Membro não encontrado.", 404));

        if ("owner".equals(member.getMembershipRole())) {
            throw new DomainException("FORBIDDEN", "Não é possível remover o owner.", 403);
        }
        if (userId.equals(requestorId)) {
            throw new DomainException("FORBIDDEN", "Não é possível remover-se a si próprio.", 403);
        }

        memberRepo.delete(member);
    }

    // ── Invites ───────────────────────────────────────────────────────────────

    public record InviteDto(UUID id, String email, String membershipRole, String status,
                            String invitedByName, Instant expiresAt, Instant acceptedAt, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<InviteDto> listInvites(UUID advertiserId) {
        return jdbc.sql("""
                SELECT i.id, i.email, i.membership_role, i.accepted_at, i.expires_at,
                       i.created_at, u.full_name AS invited_by_name
                FROM properia.advertiser_team_invites i
                JOIN properia.app_users u ON u.id = i.invited_by_user_id
                WHERE i.advertiser_id = :advertiserId
                ORDER BY i.created_at
                """)
            .param("advertiserId", advertiserId)
            .query((rs, n) -> {
                var acceptedAt = rs.getTimestamp("accepted_at");
                var expiresAt = rs.getTimestamp("expires_at").toInstant();
                String status;
                if (acceptedAt != null) status = "accepted";
                else if (Instant.now().isAfter(expiresAt)) status = "expired";
                else status = "pending";
                return new InviteDto(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("email"),
                    rs.getString("membership_role"),
                    status,
                    rs.getString("invited_by_name"),
                    expiresAt,
                    acceptedAt != null ? acceptedAt.toInstant() : null,
                    rs.getTimestamp("created_at").toInstant()
                );
            })
            .list();
    }

    public InviteDto createInvite(UUID advertiserId, UUID invitedByUserId, String email, String role) {
        email = email.trim().toLowerCase();
        if (!email.contains("@")) throw new DomainException("BAD_REQUEST", "Email inválido.", 400);
        if (!Set.of("admin", "editor", "viewer", "sales").contains(role))
            throw new DomainException("BAD_REQUEST", "Role inválida.", 400);

        var finalEmail = email;
        var alreadyMember = jdbc.sql("""
                SELECT 1 FROM properia.advertiser_users au
                JOIN properia.app_users u ON u.id = au.user_id
                WHERE au.advertiser_id = :adv AND u.email = :email
                """)
            .param("adv", advertiserId)
            .param("email", finalEmail)
            .query(Integer.class)
            .optional()
            .isPresent();

        if (alreadyMember) throw new DomainException("CONFLICT", "Este email já faz parte da equipa.", 409);

        inviteRepo.deletePendingByAdvertiserAndEmail(advertiserId, finalEmail);

        var token = generateToken();
        var expiresAt = Instant.now().plus(INVITE_TTL_DAYS, ChronoUnit.DAYS);

        var invite = new AdvertiserTeamInvite();
        invite.setAdvertiserId(advertiserId);
        invite.setInvitedByUserId(invitedByUserId);
        invite.setEmail(finalEmail);
        invite.setMembershipRole(role);
        invite.setToken(token);
        invite.setExpiresAt(expiresAt);
        invite.setCreatedAt(Instant.now());
        invite.setUpdatedAt(Instant.now());
        invite = inviteRepo.save(invite);

        var invitorName = jdbc.sql("SELECT full_name FROM properia.app_users WHERE id = :id")
            .param("id", invitedByUserId)
            .query(String.class)
            .optional()
            .orElse("—");

        var agencyName = jdbc.sql("SELECT name FROM properia.advertisers WHERE id = :id")
            .param("id", advertiserId)
            .query(String.class)
            .optional()
            .orElse("Properia");

        emailService.sendTeamInvite(finalEmail, invitorName, agencyName, role, token);

        return new InviteDto(invite.getId(), invite.getEmail(), invite.getMembershipRole(),
            "pending", invitorName, expiresAt, null, invite.getCreatedAt());
    }

    public void addMemberByEmail(UUID advertiserId, UUID requestorUserId, String email, String role) {
        if (!Set.of("admin", "editor", "sales", "viewer").contains(role))
            throw new DomainException("BAD_REQUEST", "Role inválida.", 400);

        // Only owner/admin can add directly
        var requestorRole = jdbc.sql("""
                SELECT membership_role FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", requestorUserId)
            .query(String.class).optional()
            .orElseThrow(() -> new DomainException("FORBIDDEN", "Sem permissão.", 403));
        if (!Set.of("owner", "admin").contains(requestorRole))
            throw new DomainException("FORBIDDEN", "Apenas owner ou admin podem adicionar membros directamente.", 403);

        var finalEmail = email.trim().toLowerCase();
        var userId = jdbc.sql("SELECT id FROM properia.app_users WHERE email = :email")
            .param("email", finalEmail).query(UUID.class).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Utilizador não encontrado: " + finalEmail, 404));

        var alreadyMember = jdbc.sql("""
                SELECT 1 FROM properia.advertiser_users
                WHERE advertiser_id = :adv AND user_id = :uid
                """).param("adv", advertiserId).param("uid", userId)
            .query(Integer.class).optional().isPresent();
        if (alreadyMember) throw new DomainException("CONFLICT", "Utilizador já é membro.", 409);

        var member = new AdvertiserUser();
        member.setAdvertiserId(advertiserId);
        member.setUserId(userId);
        member.setMembershipRole(role);
        member.setCreatedAt(Instant.now());
        memberRepo.save(member);
    }

    public void cancelInvite(UUID advertiserId, UUID inviteId) {
        var invite = inviteRepo.findByAdvertiserIdAndId(advertiserId, inviteId)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Convite não encontrado.", 404));
        inviteRepo.delete(invite);
    }

    public InviteDto resendInvite(UUID advertiserId, UUID inviteId, UUID requestorId) {
        var invite = inviteRepo.findByAdvertiserIdAndId(advertiserId, inviteId)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Convite não encontrado.", 404));

        if (invite.getAcceptedAt() != null)
            throw new DomainException("CONFLICT", "Este convite já foi aceite.", 409);

        var token = generateToken();
        var expiresAt = Instant.now().plus(INVITE_TTL_DAYS, ChronoUnit.DAYS);
        invite.setToken(token);
        invite.setExpiresAt(expiresAt);
        invite.setUpdatedAt(Instant.now());
        inviteRepo.save(invite);

        var invitorName = jdbc.sql("SELECT full_name FROM properia.app_users WHERE id = :id")
            .param("id", requestorId)
            .query(String.class)
            .optional()
            .orElse("—");

        var agencyName = jdbc.sql("SELECT name FROM properia.advertisers WHERE id = :id")
            .param("id", advertiserId)
            .query(String.class)
            .optional()
            .orElse("Properia");

        emailService.sendTeamInvite(invite.getEmail(), invitorName, agencyName, invite.getMembershipRole(), token);

        return new InviteDto(invite.getId(), invite.getEmail(), invite.getMembershipRole(),
            "pending", invitorName, expiresAt, null, invite.getCreatedAt());
    }

    // ── Invite acceptance (public token flow) ─────────────────────────────────

    public void acceptInvite(String token, UUID acceptingUserId) {
        var invite = inviteRepo.findByToken(token)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Convite inválido ou expirado.", 404));

        if (!invite.isPending())
            throw new DomainException("CONFLICT", "Convite inválido ou expirado.", 409);

        var id = new AdvertiserUserId(invite.getAdvertiserId(), acceptingUserId);
        if (memberRepo.existsById(id))
            throw new DomainException("CONFLICT", "Já é membro desta organização.", 409);

        var member = new AdvertiserUser();
        member.setAdvertiserId(invite.getAdvertiserId());
        member.setUserId(acceptingUserId);
        member.setMembershipRole(invite.getMembershipRole());
        member.setCreatedAt(Instant.now());
        memberRepo.save(member);

        invite.setAcceptedAt(Instant.now());
        invite.setAcceptedByUserId(acceptingUserId);
        inviteRepo.save(invite);
    }

    private String generateToken() {
        var bytes = new byte[32];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
