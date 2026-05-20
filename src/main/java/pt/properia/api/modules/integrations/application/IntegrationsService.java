package pt.properia.api.modules.integrations.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.shared.domain.DomainException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class IntegrationsService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SecureRandom rng = new SecureRandom();

    public IntegrationsService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record IntegrationDto(UUID id, UUID advertiserId, String integrationType, String channel,
                                 String status, String inboundToken, Map<String, Object> settings,
                                 Instant lastSyncAt, Integer totalLeadsImported,
                                 Instant createdAt, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public List<IntegrationDto> listIntegrations(UUID advertiserId) {
        return jdbc.sql("""
                SELECT id, advertiser_id, integration_type, channel, status,
                       inbound_token, settings::text, last_sync_at, total_leads_imported,
                       created_at, updated_at
                FROM properia.advertiser_integrations
                WHERE advertiser_id = :adv
                ORDER BY created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new IntegrationDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("advertiser_id")),
                rs.getString("integration_type"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("inbound_token"),
                parseJson(rs.getString("settings")),
                rs.getTimestamp("last_sync_at") != null ? rs.getTimestamp("last_sync_at").toInstant() : null,
                rs.getInt("total_leads_imported"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .list();
    }

    @Transactional(readOnly = true)
    public IntegrationDto getIntegration(UUID advertiserId, UUID id) {
        return jdbc.sql("""
                SELECT id, advertiser_id, integration_type, channel, status,
                       inbound_token, settings::text, last_sync_at, total_leads_imported,
                       created_at, updated_at
                FROM properia.advertiser_integrations
                WHERE advertiser_id = :adv AND id = :id
                """)
            .param("adv", advertiserId)
            .param("id", id)
            .query((rs, n) -> new IntegrationDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("advertiser_id")),
                rs.getString("integration_type"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("inbound_token"),
                parseJson(rs.getString("settings")),
                rs.getTimestamp("last_sync_at") != null ? rs.getTimestamp("last_sync_at").toInstant() : null,
                rs.getInt("total_leads_imported"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Integração não encontrada.", 404));
    }

    public IntegrationDto createIntegration(UUID advertiserId, String integrationType,
                                            String channel, Map<String, Object> settings) {
        var id = UUID.randomUUID();
        var token = generateToken();
        var settingsJson = toJson(settings);
        var now = Instant.now();

        jdbc.sql("""
                INSERT INTO properia.advertiser_integrations
                  (id, advertiser_id, integration_type, channel, status, inbound_token,
                   credentials, settings, total_leads_imported, created_at, updated_at)
                VALUES (:id, :adv,
                        :type::properia.advertiser_integration_type,
                        :channel::properia.advertiser_integration_channel,
                        'pending_setup', :token, '{}'::jsonb, :settings::jsonb, 0, :now, :now)
                ON CONFLICT (advertiser_id, integration_type, channel) DO NOTHING
                """)
            .param("id", id)
            .param("adv", advertiserId)
            .param("type", integrationType)
            .param("channel", channel)
            .param("token", token)
            .param("settings", settingsJson)
            .param("now", now)
            .update();

        return listIntegrations(advertiserId).stream()
            .filter(i -> i.integrationType().equals(integrationType) && i.channel().equals(channel))
            .findFirst()
            .orElseThrow(() -> new DomainException("INTERNAL_ERROR", "Erro ao criar integração.", 500));
    }

    public IntegrationDto updateIntegration(UUID advertiserId, UUID id, Map<String, Object> settings,
                                            String status) {
        var settingsJson = toJson(settings);
        jdbc.sql("""
                UPDATE properia.advertiser_integrations
                SET settings = :settings::jsonb,
                    status = COALESCE(:status::properia.advertiser_integration_status, status),
                    updated_at = now()
                WHERE advertiser_id = :adv AND id = :id
                """)
            .param("settings", settingsJson)
            .param("status", status)
            .param("adv", advertiserId)
            .param("id", id)
            .update();
        return getIntegration(advertiserId, id);
    }

    public void deleteIntegration(UUID advertiserId, UUID id) {
        var deleted = jdbc.sql("""
                DELETE FROM properia.advertiser_integrations
                WHERE advertiser_id = :adv AND id = :id
                """)
            .param("adv", advertiserId)
            .param("id", id)
            .update();
        if (deleted == 0) throw new DomainException("NOT_FOUND", "Integração não encontrada.", 404);
    }

    private String generateToken() {
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of()); }
        catch (Exception e) { return "{}"; }
    }
}
