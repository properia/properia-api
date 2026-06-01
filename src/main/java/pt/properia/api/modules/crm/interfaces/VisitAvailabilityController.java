package pt.properia.api.modules.crm.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

@RestController
public class VisitAvailabilityController {

    private static final String[] WEEKDAY_KEYS =
        {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};

    private static final String[] PT_WEEKDAYS =
        {"Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo"};

    private static final String[] PT_MONTHS =
        {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    private final JdbcClient jdbc;

    public VisitAvailabilityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Buyer: get available slots for a listing ──────────────────────────────

    @GetMapping("/api/visitas/disponibilidade")
    public ResponseEntity<?> getListingAvailability(
            @RequestParam UUID listingId,
            @RequestParam(defaultValue = "onsite") String mode) {

        // Load listing info
        var listingOpt = jdbc.sql("""
                SELECT li.id, li.advertiser_id,
                       COALESCE(lc.visit_booking_enabled, true)  AS visit_booking_enabled,
                       COALESCE(lc.online_visit_available, false) AS online_visit_available
                FROM properia.listings li
                LEFT JOIN properia.listing_commercial lc ON lc.listing_id = li.id
                WHERE li.id = :lid AND li.status = 'published'
                """).param("lid", listingId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("advertiserId", rs.getString("advertiser_id"));
                m.put("visitBookingEnabled", rs.getBoolean("visit_booking_enabled"));
                m.put("onlineVisitAvailable", rs.getBoolean("online_visit_available"));
                return m;
            }).optional();

        if (listingOpt.isEmpty()) {
            throw new DomainException("NOT_FOUND", "Anúncio não encontrado.", 404);
        }

        var listing = listingOpt.get();
        var advertiserId = UUID.fromString(listing.get("advertiserId").toString());
        boolean visitBookingEnabled = Boolean.TRUE.equals(listing.get("visitBookingEnabled"));
        boolean onlineVisitAvailable = Boolean.TRUE.equals(listing.get("onlineVisitAvailable"));

        if (!visitBookingEnabled) {
            return ResponseEntity.ok(Map.of("data", unavailableResponse(
                listingId.toString(), mode, onlineVisitAvailable,
                "Este anúncio não está a aceitar pedidos de visita neste momento.",
                defaultSettings())));
        }

        // Load settings
        var settingsOpt = jdbc.sql("""
                SELECT timezone, slot_duration_minutes, buffer_minutes,
                       min_notice_hours, max_advance_days
                FROM properia.advertiser_visit_availability_settings
                WHERE advertiser_id = :adv
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("timezone", rs.getString("timezone"));
                m.put("slotDurationMinutes", rs.getInt("slot_duration_minutes"));
                m.put("bufferMinutes", rs.getInt("buffer_minutes"));
                m.put("minNoticeHours", rs.getInt("min_notice_hours"));
                m.put("maxAdvanceDays", rs.getInt("max_advance_days"));
                return m;
            }).optional();

        if (settingsOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("data", unavailableResponse(
                listingId.toString(), mode, onlineVisitAvailable,
                "Este anunciante ainda não configurou horários de visita.",
                defaultSettings())));
        }

        var settings = settingsOpt.get();
        String tz = settings.get("timezone").toString();
        int slotDuration = (int) settings.get("slotDurationMinutes");
        int buffer = (int) settings.get("bufferMinutes");
        int minNoticeHours = (int) settings.get("minNoticeHours");
        int maxAdvanceDays = (int) settings.get("maxAdvanceDays");

        // Load enabled rules
        var rules = jdbc.sql("""
                SELECT weekday, start_time, end_time
                FROM properia.advertiser_visit_availability_rules
                WHERE advertiser_id = :adv AND is_enabled = true
                ORDER BY weekday
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("weekday", rs.getInt("weekday"));
                m.put("startTime", rs.getString("start_time"));
                m.put("endTime", rs.getString("end_time"));
                return m;
            }).list();

        if (rules.isEmpty()) {
            return ResponseEntity.ok(Map.of("data", unavailableResponse(
                listingId.toString(), mode, onlineVisitAvailable,
                "Este anunciante ainda não definiu horários disponíveis.",
                settings)));
        }

        // Build weekday → rule map
        var ruleByWeekday = new HashMap<Integer, Map<String, Object>>();
        for (var r : rules) ruleByWeekday.put((int) r.get("weekday"), r);

        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.of("Europe/Lisbon");
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        Instant earliest = now.plusHours(minNoticeHours).toInstant();
        LocalDate today = now.toLocalDate();
        LocalDate rangeEnd = today.plusDays(maxAdvanceDays);

        // Load future blocks in date range
        var blocks = jdbc.sql("""
                SELECT starts_at, ends_at
                FROM properia.advertiser_visit_availability_blocks
                WHERE advertiser_id = :adv
                  AND ends_at > :from
                  AND starts_at < :to
                """).param("adv", advertiserId)
            .param("from", java.sql.Timestamp.from(now.toInstant()))
            .param("to", java.sql.Timestamp.from(rangeEnd.atStartOfDay(zone).toInstant()))
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("startsAt", rs.getTimestamp("starts_at").toInstant());
                m.put("endsAt", rs.getTimestamp("ends_at").toInstant());
                return m;
            }).list();

        // Load existing requested/confirmed/waitlisted visits for this listing
        var bookedSlots = jdbc.sql("""
                SELECT starts_at
                FROM properia.visits
                WHERE listing_id = :lid
                  AND status IN ('requested', 'confirmed', 'waitlist')
                  AND starts_at > :from
                  AND starts_at < :to
                """).param("lid", listingId)
            .param("from", java.sql.Timestamp.from(now.toInstant()))
            .param("to", java.sql.Timestamp.from(rangeEnd.atStartOfDay(zone).toInstant()))
            .query((rs, n) -> (Instant) rs.getTimestamp("starts_at").toInstant())
            .list();

        var bookedSet = new HashSet<>(bookedSlots);

        // Generate slots
        var days = new ArrayList<Map<String, Object>>();
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");

        for (LocalDate date = today.plusDays(1); !date.isAfter(rangeEnd); date = date.plusDays(1)) {
            // 0=Mon, 6=Sun
            int weekday = date.getDayOfWeek().getValue() - 1;
            var rule = ruleByWeekday.get(weekday);
            if (rule == null) continue;

            LocalTime startTime = LocalTime.parse(rule.get("startTime").toString());
            LocalTime endTime = LocalTime.parse(rule.get("endTime").toString());

            var slots = new ArrayList<Map<String, Object>>();
            ZonedDateTime cursor = ZonedDateTime.of(date, startTime, zone);
            ZonedDateTime dayEnd = ZonedDateTime.of(date, endTime, zone);

            while (!cursor.plusMinutes(slotDuration).isAfter(dayEnd)) {
                Instant slotStart = cursor.toInstant();
                Instant slotEnd = cursor.plusMinutes(slotDuration).toInstant();

                boolean ok = !slotStart.isBefore(earliest)
                    && !isBlocked(slotStart, slotEnd, blocks)
                    && !bookedSet.contains(slotStart);

                if (ok) {
                    var slot = new LinkedHashMap<String, Object>();
                    slot.put("startsAt", slotStart.toString());
                    slot.put("endsAt", slotEnd.toString());
                    slot.put("timeLabel", cursor.format(timeFmt));
                    slots.add(slot);
                }

                cursor = cursor.plusMinutes((long) slotDuration + buffer);
            }

            if (!slots.isEmpty()) {
                String dayLabel = PT_WEEKDAYS[weekday] + ", " + date.getDayOfMonth()
                    + " " + PT_MONTHS[date.getMonthValue() - 1];
                days.add(Map.of(
                    "date", date.toString(),
                    "weekday", WEEKDAY_KEYS[weekday],
                    "label", dayLabel,
                    "slots", slots
                ));
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("listingId", listingId.toString());
        result.put("bookingEnabled", true);
        result.put("availabilityConfigured", true);
        result.put("acceptingRequests", true);
        result.put("onlineVisitAvailable", onlineVisitAvailable);
        result.put("mode", mode);
        result.put("publicMessage", null);
        result.put("settings", settings);
        result.put("days", days);

        return ResponseEntity.ok(Map.of("data", result));
    }

    // ── Advertiser: get availability config ───────────────────────────────────

    @GetMapping("/api/advertiser/visitas/disponibilidade")
    public ResponseEntity<?> getAdvertiserAvailability(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);

        var settingsOpt = jdbc.sql("""
                SELECT timezone, slot_duration_minutes, buffer_minutes,
                       min_notice_hours, max_advance_days
                FROM properia.advertiser_visit_availability_settings
                WHERE advertiser_id = :adv
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("timezone", rs.getString("timezone"));
                m.put("slotDurationMinutes", rs.getInt("slot_duration_minutes"));
                m.put("bufferMinutes", rs.getInt("buffer_minutes"));
                m.put("minNoticeHours", rs.getInt("min_notice_hours"));
                m.put("maxAdvanceDays", rs.getInt("max_advance_days"));
                return m;
            }).optional();

        var rules = jdbc.sql("""
                SELECT weekday, is_enabled, start_time, end_time
                FROM properia.advertiser_visit_availability_rules
                WHERE advertiser_id = :adv
                ORDER BY weekday
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                int wd = rs.getInt("weekday");
                m.put("weekday", WEEKDAY_KEYS[wd]);
                m.put("enabled", rs.getBoolean("is_enabled"));
                m.put("startTime", rs.getString("start_time"));
                m.put("endTime", rs.getString("end_time"));
                return (Map<String, Object>) m;
            }).list();

        var blocks = jdbc.sql("""
                SELECT id, starts_at, ends_at, reason, source
                FROM properia.advertiser_visit_availability_blocks
                WHERE advertiser_id = :adv AND ends_at > now()
                ORDER BY starts_at
                """).param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("startsAt", rs.getTimestamp("starts_at").toInstant().toString());
                m.put("endsAt", rs.getTimestamp("ends_at").toInstant().toString());
                m.put("reason", rs.getString("reason"));
                m.put("source", rs.getString("source"));
                return (Map<String, Object>) m;
            }).list();

        var result = new LinkedHashMap<String, Object>();
        result.put("availabilityConfigured", settingsOpt.isPresent() && !rules.isEmpty());
        result.put("settings", settingsOpt.orElse(defaultSettings()));
        result.put("rules", rules.isEmpty() ? defaultRules() : rules);
        result.put("blocks", blocks);

        return ResponseEntity.ok(Map.of("data", result));
    }

    // ── Advertiser: update availability config ────────────────────────────────

    @PatchMapping("/api/advertiser/visitas/disponibilidade")
    public ResponseEntity<?> updateAdvertiserAvailability(
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {

        var advertiserId = requireAdvertiserId(claims);

        // Upsert settings
        if (body.containsKey("settings")) {
            @SuppressWarnings("unchecked")
            var s = (Map<String, Object>) body.get("settings");
            jdbc.sql("""
                    INSERT INTO properia.advertiser_visit_availability_settings
                      (id, advertiser_id, timezone, slot_duration_minutes, buffer_minutes,
                       min_notice_hours, max_advance_days, created_at, updated_at)
                    VALUES (gen_random_uuid(), :adv, :tz, :slot, :buf, :notice, :advance, now(), now())
                    ON CONFLICT (advertiser_id) DO UPDATE SET
                      timezone = EXCLUDED.timezone,
                      slot_duration_minutes = EXCLUDED.slot_duration_minutes,
                      buffer_minutes = EXCLUDED.buffer_minutes,
                      min_notice_hours = EXCLUDED.min_notice_hours,
                      max_advance_days = EXCLUDED.max_advance_days,
                      updated_at = now()
                    """)
                .param("adv", advertiserId)
                .param("tz", s.getOrDefault("timezone", "Europe/Lisbon"))
                .param("slot", toInt(s.getOrDefault("slotDurationMinutes", 45)))
                .param("buf", toInt(s.getOrDefault("bufferMinutes", 15)))
                .param("notice", toInt(s.getOrDefault("minNoticeHours", 12)))
                .param("advance", toInt(s.getOrDefault("maxAdvanceDays", 30)))
                .update();
        }

        // Replace rules
        if (body.containsKey("rules")) {
            @SuppressWarnings("unchecked")
            var rulesList = (List<Map<String, Object>>) body.get("rules");
            jdbc.sql("DELETE FROM properia.advertiser_visit_availability_rules WHERE advertiser_id = :adv")
                .param("adv", advertiserId).update();

            for (var rule : rulesList) {
                String weekdayStr = rule.getOrDefault("weekday", "monday").toString();
                int weekdayInt = weekdayIndex(weekdayStr);
                jdbc.sql("""
                        INSERT INTO properia.advertiser_visit_availability_rules
                          (id, advertiser_id, weekday, is_enabled, start_time, end_time, created_at, updated_at)
                        VALUES (gen_random_uuid(), :adv, :wd, :enabled, :start, :end, now(), now())
                        """)
                    .param("adv", advertiserId)
                    .param("wd", weekdayInt)
                    .param("enabled", Boolean.TRUE.equals(rule.get("enabled")))
                    .param("start", rule.getOrDefault("startTime", "09:00").toString())
                    .param("end", rule.getOrDefault("endTime", "18:00").toString())
                    .update();
            }
        }

        // Create block
        if (body.containsKey("createBlock") && body.get("createBlock") != null) {
            @SuppressWarnings("unchecked")
            var block = (Map<String, Object>) body.get("createBlock");
            jdbc.sql("""
                    INSERT INTO properia.advertiser_visit_availability_blocks
                      (id, advertiser_id, starts_at, ends_at, reason, source,
                       created_by_user_id, created_at, updated_at)
                    VALUES (gen_random_uuid(), :adv, :start::timestamptz, :end::timestamptz,
                            :reason, 'manual', :uid, now(), now())
                    """)
                .param("adv", advertiserId)
                .param("start", block.get("startsAt").toString())
                .param("end", block.get("endsAt").toString())
                .param("reason", block.get("reason"))
                .param("uid", claims.userId())
                .update();
        }

        // Delete blocks
        if (body.containsKey("deleteBlockIds") && body.get("deleteBlockIds") instanceof List<?> ids) {
            for (var id : ids) {
                jdbc.sql("DELETE FROM properia.advertiser_visit_availability_blocks WHERE id = :id::uuid AND advertiser_id = :adv")
                    .param("id", id.toString())
                    .param("adv", advertiserId)
                    .update();
            }
        }

        // Return updated availability so the frontend can sync state
        return getAdvertiserAvailability(claims);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean isBlocked(Instant slotStart, Instant slotEnd,
                               List<?> blocks) {
        for (var rawBlock : blocks) {
            @SuppressWarnings("unchecked")
            var block = (Map<String, Object>) rawBlock;
            Instant bStart = (Instant) block.get("startsAt");
            Instant bEnd = (Instant) block.get("endsAt");
            if (slotStart.isBefore(bEnd) && slotEnd.isAfter(bStart)) return true;
        }
        return false;
    }

    private Map<String, Object> unavailableResponse(String listingId, String mode,
                                                     boolean onlineVisitAvailable,
                                                     String publicMessage,
                                                     Map<String, Object> settings) {
        var r = new LinkedHashMap<String, Object>();
        r.put("listingId", listingId);
        r.put("bookingEnabled", false);
        r.put("availabilityConfigured", false);
        r.put("acceptingRequests", false);
        r.put("onlineVisitAvailable", onlineVisitAvailable);
        r.put("mode", mode);
        r.put("publicMessage", publicMessage);
        r.put("settings", settings);
        r.put("days", List.of());
        return r;
    }

    private LinkedHashMap<String, Object> defaultSettings() {
        var m = new LinkedHashMap<String, Object>();
        m.put("timezone", "Europe/Lisbon");
        m.put("slotDurationMinutes", 45);
        m.put("bufferMinutes", 15);
        m.put("minNoticeHours", 12);
        m.put("maxAdvanceDays", 30);
        return m;
    }

    private List<Map<String, Object>> defaultRules() {
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 7; i++) {
            result.add(Map.of(
                "weekday", WEEKDAY_KEYS[i],
                "enabled", i < 5,
                "startTime", "09:00",
                "endTime", "18:00"
            ));
        }
        return result;
    }

    private int weekdayIndex(String weekday) {
        for (int i = 0; i < WEEKDAY_KEYS.length; i++) {
            if (WEEKDAY_KEYS[i].equals(weekday)) return i;
        }
        return 0;
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
