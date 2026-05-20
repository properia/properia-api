package pt.properia.api.modules.crmimport.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class CrmImportService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CrmImportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Batch records ─────────────────────────────────────────────────────────

    public record BatchDto(UUID id, UUID advertiserId, String sourceFamily, String sourceChannel,
                           String ingestionMethod, String status, String fileName,
                           int totalRows, int createdRows, int mergedRows, int rejectedRows,
                           Instant createdAt, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public List<BatchDto> listBatches(UUID advertiserId) {
        return jdbc.sql("""
                SELECT id, advertiser_id, source_family, source_channel, ingestion_method,
                       status, file_name, total_rows, created_rows, merged_rows, rejected_rows,
                       created_at, updated_at
                FROM properia.crm_import_batches
                WHERE advertiser_id = :adv
                ORDER BY created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new BatchDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("advertiser_id")),
                rs.getString("source_family"),
                rs.getString("source_channel"),
                rs.getString("ingestion_method"),
                rs.getString("status"),
                rs.getString("file_name"),
                rs.getInt("total_rows"),
                rs.getInt("created_rows"),
                rs.getInt("merged_rows"),
                rs.getInt("rejected_rows"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .list();
    }

    @Transactional(readOnly = true)
    public BatchDto getBatch(UUID advertiserId, UUID batchId) {
        return jdbc.sql("""
                SELECT id, advertiser_id, source_family, source_channel, ingestion_method,
                       status, file_name, total_rows, created_rows, merged_rows, rejected_rows,
                       created_at, updated_at
                FROM properia.crm_import_batches
                WHERE advertiser_id = :adv AND id = :id
                """)
            .param("adv", advertiserId)
            .param("id", batchId)
            .query((rs, n) -> new BatchDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("advertiser_id")),
                rs.getString("source_family"),
                rs.getString("source_channel"),
                rs.getString("ingestion_method"),
                rs.getString("status"),
                rs.getString("file_name"),
                rs.getInt("total_rows"),
                rs.getInt("created_rows"),
                rs.getInt("merged_rows"),
                rs.getInt("rejected_rows"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Batch não encontrado.", 404));
    }

    public BatchDto createBatch(UUID advertiserId, UUID createdByUserId, String sourceFamily,
                                String sourceChannel, String ingestionMethod, String fileName) {
        var id = UUID.randomUUID();
        var now = Instant.now();
        jdbc.sql("""
                INSERT INTO properia.crm_import_batches
                  (id, advertiser_id, created_by_user_id, source_family, source_channel,
                   ingestion_method, status, file_name, total_rows, created_rows, merged_rows,
                   rejected_rows, metadata, created_at, updated_at)
                VALUES (:id, :adv, :usr, :sf::properia.crm_import_source_family,
                        :sc::properia.crm_import_source_channel,
                        :im::properia.crm_import_ingestion_method,
                        'processing', :fn, 0, 0, 0, 0, '{}'::jsonb, :now, :now)
                """)
            .param("id", id)
            .param("adv", advertiserId)
            .param("usr", createdByUserId)
            .param("sf", sourceFamily)
            .param("sc", sourceChannel)
            .param("im", ingestionMethod)
            .param("fn", fileName)
            .param("now", now)
            .update();
        return getBatch(advertiserId, id);
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    public record ItemDto(UUID id, UUID batchId, String matchStatus, String importAction,
                          Double confidenceScore, String decisionReason, String errorMessage,
                          Map<String, Object> normalizedPayload, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<ItemDto> listItems(UUID advertiserId, UUID batchId, String matchStatus) {
        return jdbc.sql("""
                SELECT i.id, i.batch_id, i.match_status, i.import_action,
                       i.confidence_score, i.decision_reason, i.error_message,
                       i.normalized_payload::text, i.created_at
                FROM properia.crm_import_items i
                WHERE i.advertiser_id = :adv AND i.batch_id = :batch
                  AND (:ms IS NULL OR i.match_status = :ms)
                ORDER BY i.created_at
                """)
            .param("adv", advertiserId)
            .param("batch", batchId)
            .param("ms", matchStatus)
            .query((rs, n) -> new ItemDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("batch_id")),
                rs.getString("match_status"),
                rs.getString("import_action"),
                rs.getObject("confidence_score") != null ? rs.getDouble("confidence_score") : null,
                rs.getString("decision_reason"),
                rs.getString("error_message"),
                parseJson(rs.getString("normalized_payload")),
                rs.getTimestamp("created_at").toInstant()
            ))
            .list();
    }

    public void applyItemDecision(UUID advertiserId, UUID itemId, String action, String reason) {
        var valid = Set.of("created", "merged", "skipped", "review_required", "failed");
        if (!valid.contains(action)) throw new DomainException("BAD_REQUEST", "Ação inválida.", 400);

        var updated = jdbc.sql("""
                UPDATE properia.crm_import_items
                SET import_action = :action::properia.crm_import_action,
                    decision_reason = :reason,
                    updated_at = now()
                WHERE advertiser_id = :adv AND id = :id
                """)
            .param("action", action)
            .param("reason", reason)
            .param("adv", advertiserId)
            .param("id", itemId)
            .update();

        if (updated == 0) throw new DomainException("NOT_FOUND", "Item não encontrado.", 404);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }
}
