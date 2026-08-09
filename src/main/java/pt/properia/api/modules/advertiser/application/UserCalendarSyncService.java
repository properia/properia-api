package pt.properia.api.modules.advertiser.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sincronização Properia <-> agenda pessoal do consultor, agnóstica de provider (Google ou
 * Microsoft — ver CalendarProviderClient). Distinto da sala de Meet da AGÊNCIA
 * (GoogleCalendarService.createMeetEvent, chamado a partir de VisitController), que continua
 * um caminho à parte, específico do Google.
 *
 * Toda a escrita é best-effort: uma falha aqui nunca deve impedir confirmar/reagendar/cancelar
 * uma visita — só regista aviso e segue em frente (mesma filosofia de tryCreateGoogleMeet em
 * VisitController).
 */
@Service
public class UserCalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(UserCalendarSyncService.class);
    private static final DateTimeFormatter ISO_LOCAL =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("Europe/Lisbon"));

    private final JdbcClient jdbc;
    private final Map<String, CalendarProviderClient> clients;

    public UserCalendarSyncService(JdbcClient jdbc, GoogleCalendarService google, MicrosoftCalendarService microsoft) {
        this.jdbc = jdbc;
        this.clients = Map.of("google", google, "microsoft", microsoft);
    }

    private CalendarProviderClient resolveClient(String provider) {
        var client = clients.get(provider);
        if (client == null) throw new IllegalStateException("Provider de calendário desconhecido: " + provider);
        return client;
    }

    // ── Ligação ativa + access token ─────────────────────────────────────────

    /** Ligação ativa de um consultor com um access token já válido (refrescado se necessário). */
    public record ActiveConnection(CalendarProviderClient client, String provider, String accessToken, String accountEmail) {}

    public Optional<ActiveConnection> getActiveConnection(UUID userId) {
        var conn = jdbc.sql("""
                SELECT provider, account_email, access_token_encrypted, refresh_token_encrypted, token_expires_at
                FROM properia.user_calendar_connections
                WHERE user_id = :uid AND status = 'active'
                ORDER BY updated_at DESC
                LIMIT 1
                """)
            .param("uid", userId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("provider", rs.getString("provider"));
                m.put("accountEmail", rs.getString("account_email"));
                m.put("access", rs.getString("access_token_encrypted"));
                m.put("refresh", rs.getString("refresh_token_encrypted"));
                m.put("expiresAt", rs.getTimestamp("token_expires_at"));
                return m;
            }).optional().orElse(null);

        if (conn == null) return Optional.empty();
        var provider = (String) conn.get("provider");
        var accountEmail = (String) conn.get("accountEmail");

        CalendarProviderClient client;
        try {
            client = resolveClient(provider);
        } catch (IllegalStateException e) {
            log.warn("user_calendar.unknown_provider: user={} provider={}", userId, provider);
            return Optional.empty();
        }

        try {
            var accessToken = client.decrypt((String) conn.get("access"));
            if (conn.get("expiresAt") instanceof Timestamp exp && exp.toInstant().isBefore(Instant.now().plusSeconds(60))) {
                var refreshToken = client.decrypt((String) conn.get("refresh"));
                var refreshed = client.refresh(refreshToken);
                accessToken = refreshed.accessToken();
                jdbc.sql("""
                        UPDATE properia.user_calendar_connections
                        SET access_token_encrypted = :access, refresh_token_encrypted = :refresh,
                            token_expires_at = :expires, updated_at = now()
                        WHERE user_id = :uid AND provider = :provider
                        """)
                    .param("access", client.encrypt(refreshed.accessToken()))
                    .param("refresh", client.encrypt(refreshed.refreshToken()))
                    .param("expires", Timestamp.from(Instant.now().plusSeconds(3600)))
                    .param("uid", userId)
                    .param("provider", provider)
                    .update();
            }
            return Optional.of(new ActiveConnection(client, provider, accessToken, accountEmail));
        } catch (Exception e) {
            log.warn("user_calendar.refresh_failed: user={} provider={} err={}", userId, provider, e.getMessage());
            // Refresh token inválido/revogado do lado do provider — marca a ligação para o
            // consultor perceber que precisa de voltar a ligar, em vez de falhar sempre em silêncio.
            jdbc.sql("""
                    UPDATE properia.user_calendar_connections
                    SET status = 'error', updated_at = now()
                    WHERE user_id = :uid AND provider = :provider
                    """).param("uid", userId).param("provider", provider).update();
            return Optional.empty();
        }
    }

    // ── Sync da visita para a agenda do consultor ───────────────────────────────

    public void syncVisitToConsultantCalendar(UUID visitId) {
        try {
            var visit = loadVisitForSync(visitId);
            if (visit == null) return;
            if (!"confirmed".equals(visit.get("status"))) return; // só sincroniza visitas confirmadas

            var consultantId = (UUID) visit.get("consultantId");
            if (consultantId == null) return; // lead ainda sem consultor atribuído — nada a sincronizar

            var connOpt = getActiveConnection(consultantId);
            if (connOpt.isEmpty()) return; // consultor não ligou nenhuma agenda pessoal
            var conn = connOpt.get();

            var startsAt = ((Timestamp) visit.get("startsAt")).toInstant();
            var endsAt = visit.get("endsAt") instanceof Timestamp et ? et.toInstant() : startsAt.plusSeconds(2700);

            var listingTitle = Optional.ofNullable((String) visit.get("listingTitle")).orElse("Imóvel");
            var contactName = Optional.ofNullable((String) visit.get("contactName")).orElse("Cliente");
            var summary = "Visita Properia: " + listingTitle + " — " + contactName;
            var location = buildLocation(visit);
            var description = buildDescription(visit);

            var startIso = ISO_LOCAL.format(startsAt);
            var endIso = ISO_LOCAL.format(endsAt);
            var existingEventId = (String) visit.get("consultantCalendarEventId");

            String eventId;
            if (existingEventId != null && !existingEventId.isBlank()) {
                conn.client().updateEvent(conn.accessToken(), existingEventId, summary, location, description, startIso, endIso, "Europe/Lisbon");
                eventId = existingEventId;
            } else {
                eventId = conn.client().insertEvent(conn.accessToken(), summary, location, description, startIso, endIso, "Europe/Lisbon").eventId();
            }

            jdbc.sql("""
                    UPDATE properia.visits
                    SET consultant_calendar_event_id = :eventId, consultant_calendar_synced_at = now()
                    WHERE id = :id
                    """)
                .param("eventId", eventId).param("id", visitId).update();

        } catch (Exception e) {
            log.warn("user_calendar.visit_sync_failed: visit={} err={}", visitId, e.getMessage());
        }
    }

    public void removeVisitFromConsultantCalendar(UUID visitId) {
        try {
            var row = jdbc.sql("""
                    SELECT v.consultant_calendar_event_id, l.assigned_to
                    FROM properia.visits v
                    LEFT JOIN properia.leads l ON l.id = v.lead_id
                    WHERE v.id = :id
                    """)
                .param("id", visitId)
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("eventId", rs.getString("consultant_calendar_event_id"));
                    m.put("consultantId", rs.getString("assigned_to"));
                    return m;
                }).optional().orElse(null);

            if (row == null || row.get("eventId") == null) return;

            var consultantId = row.get("consultantId") != null ? UUID.fromString((String) row.get("consultantId")) : null;
            if (consultantId != null) {
                getActiveConnection(consultantId).ifPresent(conn -> {
                    try {
                        conn.client().deleteEvent(conn.accessToken(), (String) row.get("eventId"));
                    } catch (Exception e) {
                        log.warn("user_calendar.visit_delete_failed: visit={} err={}", visitId, e.getMessage());
                    }
                });
            }

            jdbc.sql("""
                    UPDATE properia.visits
                    SET consultant_calendar_event_id = NULL, consultant_calendar_synced_at = now()
                    WHERE id = :id
                    """).param("id", visitId).update();
        } catch (Exception e) {
            log.warn("user_calendar.visit_remove_failed: visit={} err={}", visitId, e.getMessage());
        }
    }

    // ── Leitura de disponibilidade (provider -> Properia) ────────────────────

    /** Intervalos ocupados do consultor no período dado. Devolve lista vazia se não ligado ou em erro (nunca bloqueia o fluxo). */
    public List<CalendarProviderClient.BusyInterval> getBusyIntervals(UUID consultantId, Instant from, Instant to) {
        if (consultantId == null) return List.of();
        var connOpt = getActiveConnection(consultantId);
        if (connOpt.isEmpty()) return List.of();
        var conn = connOpt.get();
        try {
            return conn.client().queryFreeBusy(conn.accessToken(), conn.accountEmail(), from.toString(), to.toString());
        } catch (Exception e) {
            log.warn("user_calendar.freebusy_failed: user={} provider={} err={}", consultantId, conn.provider(), e.getMessage());
            return List.of();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadVisitForSync(UUID visitId) {
        return jdbc.sql("""
                SELECT v.status::text, v.starts_at, v.ends_at, v.mode, v.meeting_url, v.consultant_calendar_event_id,
                       l.assigned_to, l.contact_name, l.contact_phone, l.contact_email,
                       li.title AS listing_title, li.city, li.district
                FROM properia.visits v
                LEFT JOIN properia.leads l ON l.id = v.lead_id
                LEFT JOIN properia.listings li ON li.id = v.listing_id
                WHERE v.id = :id
                """)
            .param("id", visitId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("status", rs.getString("status"));
                m.put("startsAt", rs.getTimestamp("starts_at"));
                m.put("endsAt", rs.getTimestamp("ends_at"));
                m.put("mode", rs.getString("mode"));
                m.put("meetingUrl", rs.getString("meeting_url"));
                m.put("consultantCalendarEventId", rs.getString("consultant_calendar_event_id"));
                var assignedTo = rs.getString("assigned_to");
                m.put("consultantId", assignedTo != null ? UUID.fromString(assignedTo) : null);
                m.put("contactName", rs.getString("contact_name"));
                m.put("contactPhone", rs.getString("contact_phone"));
                m.put("contactEmail", rs.getString("contact_email"));
                m.put("listingTitle", rs.getString("listing_title"));
                m.put("city", rs.getString("city"));
                m.put("district", rs.getString("district"));
                return (Map<String, Object>) m;
            }).optional().orElse(null);
    }

    private String buildLocation(Map<String, Object> visit) {
        if ("online".equals(visit.get("mode"))) {
            return Optional.ofNullable((String) visit.get("meetingUrl")).orElse("Visita online");
        }
        var city = (String) visit.get("city");
        var district = (String) visit.get("district");
        var parts = new java.util.ArrayList<String>();
        if (city != null) parts.add(city);
        if (district != null && !district.equals(city)) parts.add(district);
        return parts.isEmpty() ? "" : String.join(", ", parts);
    }

    private String buildDescription(Map<String, Object> visit) {
        var sb = new StringBuilder("Visita agendada via Properia.\n\n");
        if (visit.get("contactName") != null) sb.append("Cliente: ").append(visit.get("contactName")).append('\n');
        if (visit.get("contactPhone") != null) sb.append("Telefone: ").append(visit.get("contactPhone")).append('\n');
        if (visit.get("contactEmail") != null) sb.append("Email: ").append(visit.get("contactEmail")).append('\n');
        if ("online".equals(visit.get("mode")) && visit.get("meetingUrl") != null) {
            sb.append("\nLink da videochamada: ").append(visit.get("meetingUrl")).append('\n');
        }
        return sb.toString();
    }
}
