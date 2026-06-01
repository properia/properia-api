package pt.properia.api.modules.crm.interfaces;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
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
    private final AuthEmailService emailService;

    public VisitController(
            RequestVisitUseCase requestVisit,
            UpdateVisitStatusUseCase updateVisitStatus,
            GetVisitsUseCase getVisits,
            JdbcClient jdbc,
            AuthEmailService emailService) {
        this.requestVisit = requestVisit;
        this.updateVisitStatus = updateVisitStatus;
        this.getVisits = getVisits;
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    // ── Buyer: request a visit ──────────────────────────────────────────────

    @PostMapping("/api/visitas")
    public ResponseEntity<?> requestVisit(
            @AuthenticationPrincipal JwtClaims claims,
            @Valid @RequestBody RequestVisitRequest req) {

        requireAuth(claims);

        var listingId = java.util.UUID.fromString(req.listingId());

        // Get or create a lead for this buyer+listing
        var existingLead = jdbc.sql("""
                SELECT id FROM properia.leads
                WHERE listing_id = :lid AND user_id = :uid
                LIMIT 1
                """).param("lid", listingId).param("uid", claims.userId())
            .query((rs, n) -> rs.getString("id"))
            .optional();

        UUID leadId;
        if (existingLead.isPresent()) {
            leadId = UUID.fromString(existingLead.get());
            // Update contact info
            jdbc.sql("""
                    UPDATE properia.leads
                    SET contact_name = :name, contact_phone = :phone, updated_at = now()
                    WHERE id = :id
                    """).param("name", req.contactName())
                .param("phone", req.contactPhone())
                .param("id", leadId).update();
        } else {
            // Find advertiser from listing
            var advertiserIdStr = jdbc.sql("""
                    SELECT advertiser_id FROM properia.listings WHERE id = :lid
                    """).param("lid", listingId)
                .query((rs, n) -> rs.getString("advertiser_id"))
                .optional()
                .orElseThrow(() -> new pt.properia.api.shared.domain.DomainException("NOT_FOUND", "Anúncio não encontrado.", 404));

            leadId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO properia.leads
                      (id, listing_id, user_id, advertiser_id, source, stage, intent_type,
                       message, contact_name, contact_email, contact_phone,
                       metadata, created_at, updated_at)
                    VALUES (:id, :lid, :uid, :adv::uuid,
                            'visit_request'::properia.lead_source,
                            'new'::properia.lead_stage,
                            'buy'::properia.intent_type,
                            :msg, :name, :email, :phone, '{}', now(), now())
                    """)
                .param("id", leadId)
                .param("lid", listingId)
                .param("uid", claims.userId())
                .param("adv", advertiserIdStr)
                .param("msg", req.message())
                .param("name", req.contactName())
                .param("email", req.contactEmail())
                .param("phone", req.contactPhone())
                .update();
        }

        java.time.Instant startsAt = java.time.Instant.parse(req.slotStartsAt());
        java.time.Instant endsAt = req.slotEndsAt() != null ? java.time.Instant.parse(req.slotEndsAt()) : null;

        // Check if slot already booked (waitlist if so)
        var alreadyBooked = jdbc.sql("""
                SELECT COUNT(*) FROM properia.visits
                WHERE listing_id = :lid AND status IN ('requested','confirmed')
                  AND starts_at = :starts
                """).param("lid", listingId).param("starts", java.sql.Timestamp.from(startsAt))
            .query(Long.class).single() > 0;

        var visit = requestVisit.execute(new RequestVisitUseCase.Command(
            listingId, claims.userId(), leadId,
            req.mode() != null ? req.mode() : "onsite",
            startsAt, endsAt, req.message()
        ));

        if (alreadyBooked) {
            jdbc.sql("UPDATE properia.visits SET status = 'waitlist', updated_at = now() WHERE id = :id")
                .param("id", visit.getId()).update();
        }

        String finalStatus = alreadyBooked ? "waitlist" : visit.getStatus();

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "leadId", leadId.toString(),
            "visitId", visit.getId().toString(),
            "status", finalStatus
        )));
    }

    @GetMapping("/api/visitas")
    public ResponseEntity<?> listForBuyer(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var items = jdbc.sql("""
                SELECT v.id, v.status, v.mode, v.starts_at, v.ends_at,
                       v.meeting_url, v.notes, v.created_at, v.updated_at,
                       li.id AS li_id, li.public_id AS li_public_id, li.title AS li_title,
                       li.city AS li_city, li.district AS li_district, li.hero_image_url AS li_hero,
                       li.business_type AS li_bt,
                       a.id AS adv_id, a.brand_name AS adv_name,
                       a.email AS adv_email, a.phone AS adv_phone
                FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                LEFT JOIN properia.listings li ON li.id = v.listing_id
                LEFT JOIN properia.advertisers a ON a.id = v.advertiser_id
                WHERE l.user_id = :uid
                ORDER BY v.starts_at DESC
                """).param("uid", claims.userId())
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("status", rs.getString("status"));
                m.put("mode", rs.getString("mode"));
                m.put("startsAt", rs.getTimestamp("starts_at") != null ? rs.getTimestamp("starts_at").toInstant().toString() : null);
                m.put("endsAt", rs.getTimestamp("ends_at") != null ? rs.getTimestamp("ends_at").toInstant().toString() : null);
                m.put("meetingUrl", rs.getString("meeting_url"));
                m.put("meetingProvider", null);
                m.put("externalCalendarEventId", null);
                m.put("meetingCreatedAt", null);
                m.put("meetingSyncStatus", null);
                m.put("notes", rs.getString("notes"));
                m.put("statusReason", null);
                m.put("buyerConfirmedAt", null);
                m.put("buyerConfirmationRequestedAt", null);
                var listing = new java.util.LinkedHashMap<String, Object>();
                listing.put("id", Optional.ofNullable(rs.getString("li_id")).orElse(""));
                listing.put("publicId", Optional.ofNullable(rs.getString("li_public_id")).orElse(""));
                listing.put("title", Optional.ofNullable(rs.getString("li_title")).orElse("Imóvel"));
                listing.put("city", rs.getString("li_city"));
                listing.put("district", rs.getString("li_district"));
                listing.put("heroImageUrl", rs.getString("li_hero"));
                listing.put("businessType", Optional.ofNullable(rs.getString("li_bt")).orElse("sale"));
                m.put("listing", listing);
                var advertiser = new java.util.LinkedHashMap<String, Object>();
                advertiser.put("id", Optional.ofNullable(rs.getString("adv_id")).orElse(""));
                advertiser.put("name", Optional.ofNullable(rs.getString("adv_name")).orElse("Anunciante"));
                advertiser.put("email", rs.getString("adv_email"));
                advertiser.put("phone", rs.getString("adv_phone"));
                advertiser.put("logoUrl", null);
                m.put("advertiser", advertiser);
                return (Map<String, Object>) m;
            }).list();
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
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
    public ResponseEntity<?> listForAdvertiser(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var advertiserId = requireAdvertiserId(claims);

        var whereParts = new java.util.ArrayList<String>();
        var params = new java.util.LinkedHashMap<String, Object>();
        whereParts.add("v.advertiser_id = :adv");
        params.put("adv", advertiserId);

        if (status != null && !status.isBlank() && !"todas".equals(status)) {
            whereParts.add("v.status::text = :status");
            params.put("status", status);
        }
        if (q != null && !q.isBlank()) {
            whereParts.add("(v.notes ILIKE :q OR l.contact_name ILIKE :q OR l.contact_email ILIKE :q)");
            params.put("q", "%" + q + "%");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            whereParts.add("v.starts_at >= :dateFrom::timestamptz");
            params.put("dateFrom", dateFrom);
        }
        if (dateTo != null && !dateTo.isBlank()) {
            whereParts.add("v.starts_at <= :dateTo::timestamptz");
            params.put("dateTo", dateTo);
        }

        var whereClause = "WHERE " + String.join(" AND ", whereParts);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safePageSize;

        var joinClause = """
                FROM properia.visits v
                LEFT JOIN properia.leads l ON l.id = v.lead_id
                LEFT JOIN properia.listings li ON li.id = v.listing_id
                """;

        var countSql = "SELECT COUNT(*) " + joinClause + whereClause;
        var countQuery = jdbc.sql(countSql);
        for (var e : params.entrySet()) countQuery = countQuery.param(e.getKey(), e.getValue());
        long total = countQuery.query(Long.class).single();

        var listSql = """
                SELECT v.id, v.lead_id, v.advertiser_id, v.listing_id, v.mode, v.status,
                       v.starts_at, v.ends_at, v.meeting_url, v.notes, v.created_at, v.updated_at,
                       l.id AS l_id, l.contact_name, l.contact_email, l.contact_phone,
                       l.source AS l_source, l.stage AS l_stage,
                       li.id AS li_id, li.public_id AS li_public_id, li.title AS li_title,
                       li.business_type AS li_business_type, li.city AS li_city, li.district AS li_district
                """ + joinClause + whereClause + " ORDER BY v.starts_at DESC LIMIT :lim OFFSET :off";
        var listQuery = jdbc.sql(listSql);
        for (var e : params.entrySet()) listQuery = listQuery.param(e.getKey(), e.getValue());
        listQuery = listQuery.param("lim", safePageSize).param("off", offset);

        var items = listQuery.query((rs, n) -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", rs.getString("id"));
            m.put("leadId", rs.getString("lead_id"));
            m.put("advertiserId", rs.getString("advertiser_id"));
            m.put("listingId", rs.getString("listing_id"));
            m.put("isLocked", false);
            m.put("mode", rs.getString("mode"));
            m.put("status", rs.getString("status"));
            m.put("startsAt", rs.getTimestamp("starts_at") != null ? rs.getTimestamp("starts_at").toInstant().toString() : null);
            m.put("endsAt", rs.getTimestamp("ends_at") != null ? rs.getTimestamp("ends_at").toInstant().toString() : null);
            m.put("meetingUrl", rs.getString("meeting_url"));
            m.put("meetingProvider", null);
            m.put("externalCalendarEventId", null);
            m.put("meetingCreatedAt", null);
            m.put("meetingSyncStatus", null);
            m.put("notes", rs.getString("notes"));
            m.put("statusReason", null);
            m.put("outcome", null);
            m.put("outcomeNotes", null);
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());

            var lead = new java.util.LinkedHashMap<String, Object>();
            lead.put("id", rs.getString("l_id"));
            lead.put("contactName", rs.getString("contact_name"));
            lead.put("contactEmail", rs.getString("contact_email"));
            lead.put("contactPhone", rs.getString("contact_phone"));
            lead.put("source", rs.getString("l_source"));
            lead.put("stage", rs.getString("l_stage"));
            m.put("lead", lead);

            var listing = new java.util.LinkedHashMap<String, Object>();
            listing.put("id", Optional.ofNullable(rs.getString("li_id")).orElse(rs.getString("listing_id")));
            listing.put("publicId", Optional.ofNullable(rs.getString("li_public_id")).orElse(""));
            listing.put("title", Optional.ofNullable(rs.getString("li_title")).orElse("Imóvel"));
            listing.put("businessType", Optional.ofNullable(rs.getString("li_business_type")).orElse("sale"));
            listing.put("city", rs.getString("li_city"));
            listing.put("district", rs.getString("li_district"));
            m.put("listing", listing);

            return (Map<String, Object>) m;
        }).list();

        int totalPages = (int) Math.ceil((double) total / safePageSize);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safePageSize);
        result.put("totalPages", Math.max(1, totalPages));
        return ResponseEntity.ok(Map.of("data", result));
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
                    """).param("hash", codeHash).param("exp", java.sql.Timestamp.from(expiresAt))
                .param("now", java.sql.Timestamp.from(now)).param("id", UUID.fromString(existing.get().get("id").toString())).update();
        } else {
            jdbc.sql("""
                    INSERT INTO properia.visit_email_verifications
                      (id, user_id, email, code_hash, expires_at, last_sent_at, created_at, updated_at)
                    VALUES (:id, :uid, :email, :hash, :exp, :now, :now, :now)
                    """).param("id", UUID.randomUUID()).param("uid", claims.userId())
                .param("email", user.get("email")).param("hash", codeHash)
                .param("exp", java.sql.Timestamp.from(expiresAt)).param("now", java.sql.Timestamp.from(now)).update();
        }
        emailService.sendVisitEmailVerificationCode((String) user.get("email"), code);
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
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("codeHash", rs.getString("code_hash"));
                m.put("expiresAt", rs.getTimestamp("expires_at"));
                m.put("consumedAt", rs.getTimestamp("consumed_at"));
                m.put("failedAttempts", rs.getInt("failed_attempts"));
                return m;
            }).optional()
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
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("status", rs.getString("status"));
                m.put("startsAt", rs.getTimestamp("starts_at"));
                m.put("buyerConfirmedAt", rs.getTimestamp("buyer_confirmed_at"));
                return m;
            }).optional()
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
