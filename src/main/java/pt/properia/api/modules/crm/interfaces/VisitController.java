package pt.properia.api.modules.crm.interfaces;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.crm.application.visit.*;
import pt.properia.api.modules.crm.interfaces.request.RequestVisitRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class VisitController {

    private final RequestVisitUseCase requestVisit;
    private final UpdateVisitStatusUseCase updateVisitStatus;
    private final GetVisitsUseCase getVisits;
    private final JdbcClient jdbc;

    public VisitController(
            RequestVisitUseCase requestVisit,
            UpdateVisitStatusUseCase updateVisitStatus,
            GetVisitsUseCase getVisits,
            JdbcClient jdbc) {
        this.requestVisit = requestVisit;
        this.updateVisitStatus = updateVisitStatus;
        this.getVisits = getVisits;
        this.jdbc = jdbc;
    }

    // ── Buyer: request a visit ──────────────────────────────────────────────

    @PostMapping("/api/visitas")
    public ResponseEntity<?> requestVisit(
            @AuthenticationPrincipal JwtClaims claims,
            @Valid @RequestBody RequestVisitRequest req) {

        requireAuth(claims);
        var visit = requestVisit.execute(new RequestVisitUseCase.Command(
            req.listingId(), claims.userId(), req.leadId(),
            req.mode(), req.startsAt(), req.endsAt(), req.notes()
        ));

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", visit.getId(),
            "status", visit.getStatus(),
            "startsAt", visit.getStartsAt()
        )));
    }

    @GetMapping("/api/visitas")
    public ResponseEntity<?> listForBuyer(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        return ResponseEntity.ok(Map.of("data", getVisits.forBuyer(claims.userId())));
    }

    @GetMapping("/api/visitas/listing/{listingId}")
    public ResponseEntity<?> listForListingAndBuyer(
            @PathVariable UUID listingId,
            @AuthenticationPrincipal JwtClaims claims) {

        requireAuth(claims);
        return ResponseEntity.ok(Map.of("data",
            getVisits.forListingAndBuyer(listingId, claims.userId())));
    }

    // ── Advertiser: manage visits ───────────────────────────────────────────

    @GetMapping("/api/advertiser/visitas")
    public ResponseEntity<?> listForAdvertiser(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        return ResponseEntity.ok(Map.of("data", getVisits.forAdvertiser(advertiserId)));
    }

    @PatchMapping("/api/advertiser/visitas/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, String> body) {

        var advertiserId = requireAdvertiserId(claims);
        updateVisitStatus.execute(new UpdateVisitStatusUseCase.Command(
            id, advertiserId, body.get("status"), body.get("meetingUrl")
        ));
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    @PostMapping("/api/advertiser/visitas/{id}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody(required = false) Map<String, String> body) {

        var advertiserId = requireAdvertiserId(claims);
        var meetingUrl = body != null ? body.get("meetingUrl") : null;
        updateVisitStatus.execute(new UpdateVisitStatusUseCase.Command(id, advertiserId, "confirmed", meetingUrl));
        return ResponseEntity.ok(Map.of("data", Map.of("confirmed", true)));
    }

    @PostMapping("/api/advertiser/visitas/{id}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {

        var advertiserId = requireAdvertiserId(claims);
        updateVisitStatus.execute(new UpdateVisitStatusUseCase.Command(id, advertiserId, "cancelled", null));
        return ResponseEntity.ok(Map.of("data", Map.of("cancelled", true)));
    }

    // ── Advertiser: update individual visit ───────────────────────────────────

    @PatchMapping("/api/advertiser/visitas/{id}")
    public ResponseEntity<?> updateVisit(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {
        var advertiserId = requireAdvertiserId(claims);
        var status = body.containsKey("status") ? (String) body.get("status") : null;
        var meetingUrl = body.containsKey("meetingUrl") ? (String) body.get("meetingUrl") : null;

        if (status != null) {
            updateVisitStatus.execute(new UpdateVisitStatusUseCase.Command(id, advertiserId, status, meetingUrl));
        } else if (meetingUrl != null) {
            jdbc.sql("UPDATE properia.visits SET meeting_url = :url, updated_at = now() WHERE id = :id AND advertiser_id = :adv")
                .param("url", meetingUrl).param("id", id).param("adv", advertiserId).update();
        }
        return ResponseEntity.ok(Map.of("data", Map.of("updated", true)));
    }

    // ── Buyer: email verification for visits ──────────────────────────────────

    @GetMapping("/api/visitas/email-verification")
    public ResponseEntity<?> getEmailVerification(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var user = jdbc.sql("""
                SELECT email, email_verified_at FROM properia.app_users WHERE id = :uid
                """).param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "email", Optional.ofNullable(rs.getString("email")).orElse(""),
                "verified", rs.getTimestamp("email_verified_at") != null
            ))
            .optional()
            .orElseThrow(() -> new DomainException("UNAUTHORIZED", "Sessão inválida.", 401));
        return ResponseEntity.ok(Map.of("data", user));
    }

    @PostMapping("/api/visitas/email-verification")
    public ResponseEntity<?> sendEmailVerification(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var user = jdbc.sql("""
                SELECT id, email, email_verified_at FROM properia.app_users WHERE id = :uid
                """).param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "email", Optional.ofNullable(rs.getString("email")).orElse(""),
                "verified", rs.getTimestamp("email_verified_at") != null
            ))
            .optional()
            .orElseThrow(() -> new DomainException("UNAUTHORIZED", "Sessão inválida.", 401));

        if (Boolean.TRUE.equals(user.get("verified"))) {
            return ResponseEntity.ok(Map.of("data", Map.of(
                "email", user.get("email"), "sent", true, "cooldownSeconds", 0)));
        }

        // Check cooldown
        var now = Instant.now();
        var existing = jdbc.sql("""
                SELECT id, last_sent_at FROM properia.visit_email_verifications WHERE user_id = :uid
                """).param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "lastSentAt", (Object) rs.getTimestamp("last_sent_at")
            )).optional();

        if (existing.isPresent() && existing.get().get("lastSentAt") instanceof java.sql.Timestamp ts) {
            var elapsedMs = now.toEpochMilli() - ts.toInstant().toEpochMilli();
            if (elapsedMs < 60_000) {
                var cooldown = Math.max(1, (int) Math.ceil((60_000.0 - elapsedMs) / 1000));
                throw new DomainException("CONFLICT",
                    "Espere " + cooldown + "s antes de pedir um novo código.", 409);
            }
        }

        // Generate 6-digit code
        var code = String.format("%06d", (int) (Math.random() * 1_000_000));
        var codeHash = hashSha256(code);
        var expiresAt = now.plusSeconds(600);

        if (existing.isPresent()) {
            jdbc.sql("""
                    UPDATE properia.visit_email_verifications
                    SET code_hash = :hash, expires_at = :exp, consumed_at = NULL,
                        last_sent_at = :now, failed_attempts = 0, updated_at = :now
                    WHERE id = :id
                    """).param("hash", codeHash).param("exp", expiresAt)
                .param("now", now).param("id", existing.get().get("id")).update();
        } else {
            jdbc.sql("""
                    INSERT INTO properia.visit_email_verifications
                      (id, user_id, email, code_hash, expires_at, last_sent_at, created_at, updated_at)
                    VALUES (:id, :uid, :email, :hash, :exp, :now, :now, :now)
                    """).param("id", UUID.randomUUID()).param("uid", claims.userId())
                .param("email", user.get("email")).param("hash", codeHash)
                .param("exp", expiresAt).param("now", now).update();
        }
        // Note: in production, email is sent by async service reading from the table
        return ResponseEntity.ok(Map.of("data", Map.of(
            "email", user.get("email"), "sent", true, "cooldownSeconds", 60)));
    }

    @PostMapping("/api/visitas/email-verification/confirm")
    public ResponseEntity<?> confirmEmailVerification(@RequestBody Map<String, String> body,
                                                      @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var code = body.get("code");
        if (code == null || code.isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "Código inválido.", 422);
        }

        var verification = jdbc.sql("""
                SELECT id, code_hash, expires_at, consumed_at, failed_attempts
                FROM properia.visit_email_verifications WHERE user_id = :uid
                """).param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "codeHash", rs.getString("code_hash"),
                "expiresAt", (Object) rs.getTimestamp("expires_at"),
                "consumedAt", (Object) rs.getTimestamp("consumed_at"),
                "failedAttempts", rs.getInt("failed_attempts")
            )).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Não existe um código ativo para esta conta.", 404));

        if (verification.get("consumedAt") != null) {
            throw new DomainException("NOT_FOUND", "Não existe um código ativo para esta conta.", 404);
        }

        var now = Instant.now();
        if (verification.get("expiresAt") instanceof java.sql.Timestamp ts && ts.toInstant().isBefore(now)) {
            throw new DomainException("CONFLICT", "O código expirou. Peça um novo código.", 409);
        }

        var failedAttempts = (int) verification.get("failedAttempts");
        if (failedAttempts >= 5) {
            throw new DomainException("FORBIDDEN", "Excedeu o número de tentativas. Peça um novo código.", 403);
        }

        if (!hashSha256(code).equals(verification.get("codeHash"))) {
            jdbc.sql("""
                    UPDATE properia.visit_email_verifications
                    SET failed_attempts = failed_attempts + 1, updated_at = now()
                    WHERE id = :id
                    """).param("id", verification.get("id")).update();
            throw new DomainException("VALIDATION_ERROR", "Código inválido.", 422);
        }

        jdbc.sql("UPDATE properia.app_users SET email_verified_at = now(), updated_at = now() WHERE id = :uid")
            .param("uid", claims.userId()).update();
        jdbc.sql("""
                UPDATE properia.visit_email_verifications
                SET consumed_at = now(), failed_attempts = 0, updated_at = now() WHERE id = :id
                """).param("id", verification.get("id")).update();

        return ResponseEntity.ok(Map.of("data", Map.of("verified", true)));
    }

    // ── Buyer: confirm presence ────────────────────────────────────────────────

    @PostMapping("/api/visitas/{id}/confirm-presence")
    public ResponseEntity<?> confirmPresence(@PathVariable UUID id,
                                             @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var visit = jdbc.sql("""
                SELECT v.id, v.status, v.starts_at, v.buyer_confirmed_at
                FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE v.id = :id AND l.user_id = :uid
                """).param("id", id).param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "status", rs.getString("status"),
                "startsAt", (Object) rs.getTimestamp("starts_at"),
                "buyerConfirmedAt", (Object) rs.getTimestamp("buyer_confirmed_at")
            )).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Visita não encontrada.", 404));

        if (!"confirmed".equals(visit.get("status"))) {
            throw new DomainException("CONFLICT",
                "Só pode confirmar presença em visitas já confirmadas pelo anunciante.", 409);
        }

        var now = Instant.now();
        if (visit.get("startsAt") instanceof java.sql.Timestamp ts) {
            if (ts.toInstant().isBefore(now)) {
                throw new DomainException("CONFLICT", "Esta visita já começou ou terminou.", 409);
            }
            var windowOpens = ts.toInstant().minusSeconds(24 * 3600);
            if (now.isBefore(windowOpens)) {
                throw new DomainException("CONFLICT",
                    "A confirmação de presença abre apenas nas 24 horas anteriores à visita.", 409);
            }
        }

        if (visit.get("buyerConfirmedAt") != null) {
            return ResponseEntity.ok(Map.of("data", Map.of(
                "id", id.toString(),
                "buyerConfirmedAt", ((java.sql.Timestamp) visit.get("buyerConfirmedAt")).toInstant().toString()
            )));
        }

        jdbc.sql("""
                UPDATE properia.visits SET buyer_confirmed_at = now(), updated_at = now() WHERE id = :id
                """).param("id", id).update();

        return ResponseEntity.ok(Map.of("data", Map.of(
            "id", id.toString(),
            "buyerConfirmedAt", now.toString()
        )));
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Autenticação necessária.", 401);
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }

    private String hashSha256(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return input;
        }
    }
}
