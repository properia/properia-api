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

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record BatchDto(UUID id, UUID advertiserId, String entityType,
                           String sourceFamily, String sourceChannel,
                           String ingestionMethod, String status, String fileName,
                           int totalRows, int createdRows, int mergedRows, int rejectedRows,
                           int reviewRequiredRows, int materializedLeadCount, int materializedVisitCount,
                           Instant createdAt, Instant updatedAt) {}

    public record ItemDto(UUID id, UUID batchId, String matchStatus, String importAction,
                          Double confidenceScore, String decisionReason, String errorMessage,
                          Map<String, Object> normalizedPayload, Instant createdAt) {}

    // ── Preview records ───────────────────────────────────────────────────────

    public record MatchedListing(String id, String publicId, String title, String city, String district) {}

    public record DuplicateLead(String id, String createdAt, String stage, String contactName) {}

    public record LeadPreviewItem(
        int rowIndex,
        String importAction,   // "create" | "merge_candidate" | "review_required"
        String matchStatus,    // "matched" | "unmatched" | "ambiguous"
        List<String> reasons,
        Map<String, Object> normalized,
        MatchedListing matchedListing,
        DuplicateLead duplicateLead) {}

    public record LeadPreviewSummary(
        int totalRows, int createCount, int mergeCandidateCount,
        int reviewRequiredCount, int matchedCount, int ambiguousCount, int unmatchedCount) {}

    public record LeadPreviewResponse(LeadPreviewSummary summary, List<LeadPreviewItem> items) {}

    public record VisitPreviewItem(
        int rowIndex,
        String importAction,
        String matchStatus,
        List<String> reasons,
        Map<String, Object> normalized,
        MatchedListing matchedListing,
        Object matchedLead,
        Object duplicateVisit) {}

    public record VisitPreviewSummary(
        int totalRows, int createCount, int mergeCandidateCount,
        int reviewRequiredCount, int matchedCount, int ambiguousCount, int unmatchedCount) {}

    public record VisitPreviewResponse(VisitPreviewSummary summary, List<VisitPreviewItem> items) {}

    // ── Batch CRUD ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BatchDto> listBatches(UUID advertiserId) {
        return jdbc.sql("""
                SELECT b.id, b.advertiser_id, b.entity_type, b.source_family, b.source_channel,
                       b.ingestion_method, b.status, b.file_name,
                       b.total_rows, b.created_rows, b.merged_rows, b.rejected_rows,
                       b.review_required_rows, b.created_at, b.updated_at,
                       COUNT(DISTINCT i.lead_id) FILTER (WHERE i.lead_id IS NOT NULL) AS mat_leads,
                       0 AS mat_visits
                FROM properia.crm_import_batches b
                LEFT JOIN properia.crm_import_items i ON i.batch_id = b.id
                WHERE b.advertiser_id = :adv
                GROUP BY b.id
                ORDER BY b.created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> mapBatchDto(rs))
            .list();
    }

    @Transactional(readOnly = true)
    public BatchDto getBatch(UUID advertiserId, UUID batchId) {
        return jdbc.sql("""
                SELECT b.id, b.advertiser_id, b.entity_type, b.source_family, b.source_channel,
                       b.ingestion_method, b.status, b.file_name,
                       b.total_rows, b.created_rows, b.merged_rows, b.rejected_rows,
                       b.review_required_rows, b.created_at, b.updated_at,
                       COUNT(DISTINCT i.lead_id) FILTER (WHERE i.lead_id IS NOT NULL) AS mat_leads,
                       0 AS mat_visits
                FROM properia.crm_import_batches b
                LEFT JOIN properia.crm_import_items i ON i.batch_id = b.id
                WHERE b.advertiser_id = :adv AND b.id = :id
                GROUP BY b.id
                """)
            .param("adv", advertiserId)
            .param("id", batchId)
            .query((rs, n) -> mapBatchDto(rs))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Batch não encontrado.", 404));
    }

    private BatchDto mapBatchDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("advertiser_id")),
            rs.getString("entity_type"),
            rs.getString("source_family"),
            rs.getString("source_channel"),
            rs.getString("ingestion_method"),
            rs.getString("status"),
            rs.getString("file_name"),
            rs.getInt("total_rows"),
            rs.getInt("created_rows"),
            rs.getInt("merged_rows"),
            rs.getInt("rejected_rows"),
            rs.getInt("review_required_rows"),
            rs.getInt("mat_leads"),
            rs.getInt("mat_visits"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    // ── Preview leads (no DB writes) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public LeadPreviewResponse previewLeads(UUID advertiserId, List<Map<String, Object>> rows) {
        var items = new ArrayList<LeadPreviewItem>();
        int createCount = 0, mergeCount = 0, reviewCount = 0;
        int matchedCount = 0, ambiguousCount = 0, unmatchedCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var item = previewLeadRow(advertiserId, i, row);
            items.add(item);
            switch (item.importAction()) {
                case "create"           -> createCount++;
                case "merge_candidate"  -> mergeCount++;
                default                 -> reviewCount++;
            }
            switch (item.matchStatus()) {
                case "matched"      -> matchedCount++;
                case "ambiguous"    -> ambiguousCount++;
                default             -> unmatchedCount++;
            }
        }

        var summary = new LeadPreviewSummary(rows.size(), createCount, mergeCount, reviewCount,
            matchedCount, ambiguousCount, unmatchedCount);
        return new LeadPreviewResponse(summary, items);
    }

    private LeadPreviewItem previewLeadRow(UUID advertiserId, int rowIndex, Map<String, Object> row) {
        var reasons = new ArrayList<String>();
        var str = (java.util.function.Function<String, String>) key ->
            row.get(key) != null ? row.get(key).toString().trim() : null;

        String publicId  = str.apply("listingPublicId");
        String extId     = str.apply("listingExternalId");
        String title     = str.apply("listingTitle");
        String email     = str.apply("contactEmail");
        String phone     = str.apply("contactPhone");

        // Normalised payload
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("externalLeadId",    str.apply("externalLeadId"));
        normalized.put("listingPublicId",   publicId);
        normalized.put("listingExternalId", extId);
        normalized.put("listingTitle",      title);
        normalized.put("contactName",       str.apply("contactName"));
        normalized.put("contactEmail",      email);
        normalized.put("contactPhone",      phone);
        normalized.put("message",           str.apply("message"));
        normalized.put("receivedAt",        str.apply("receivedAt"));

        // Contact validation
        if (email == null && phone == null && str.apply("contactName") == null) {
            reasons.add("Sem dados de contacto");
            return new LeadPreviewItem(rowIndex, "review_required", "unmatched", reasons,
                normalized, null, null);
        }

        // Find listing
        MatchedListing listing = findListing(advertiserId, publicId, extId, title);
        if (listing == null) {
            reasons.add("Imóvel não encontrado");
            return new LeadPreviewItem(rowIndex, "review_required", "unmatched", reasons,
                normalized, null, null);
        }

        // Check for duplicate lead
        DuplicateLead duplicate = findDuplicateLead(UUID.fromString(listing.id()), email, phone);
        if (duplicate != null) {
            reasons.add("Lead duplicado detetado (mesmo contacto e imóvel)");
            return new LeadPreviewItem(rowIndex, "review_required", "ambiguous", reasons,
                normalized, listing, duplicate);
        }

        reasons.add("Imóvel identificado, sem duplicados");
        return new LeadPreviewItem(rowIndex, "create", "matched", reasons, normalized, listing, null);
    }

    // ── Create lead batch ─────────────────────────────────────────────────────

    public Map<String, Object> createLeadBatch(UUID advertiserId, UUID createdByUserId,
            String sourceFamily, String sourceChannel, String ingestionMethod,
            String fileName, List<Map<String, Object>> rows) {

        var preview = previewLeads(advertiserId, rows);
        var batchId = UUID.randomUUID();
        var now = java.sql.Timestamp.from(Instant.now());
        var s = preview.summary();

        int reviewRequired = s.reviewRequiredCount();
        String status = reviewRequired == 0 ? "completed" : (s.createCount() > 0 ? "partial" : "processing");

        jdbc.sql("""
                INSERT INTO properia.crm_import_batches
                  (id, advertiser_id, created_by_user_id, entity_type,
                   source_family, source_channel, ingestion_method, status, file_name,
                   total_rows, created_rows, merged_rows, rejected_rows, review_required_rows,
                   metadata, created_at, updated_at)
                VALUES (:id, :adv, :usr, 'lead',
                        :sf::properia.crm_import_source_family,
                        :sc::properia.crm_import_source_channel,
                        :im::properia.crm_import_ingestion_method,
                        :status::properia.crm_import_batch_status,
                        :fn, :total, :created, :merged, :rejected, :review,
                        '{}'::jsonb, :now, :now)
                """)
            .param("id", batchId)
            .param("adv", advertiserId)
            .param("usr", createdByUserId)
            .param("sf", sourceFamily)
            .param("sc", sourceChannel)
            .param("im", ingestionMethod)
            .param("status", status)
            .param("fn", fileName)
            .param("total", s.totalRows())
            .param("created", s.createCount())
            .param("merged", s.mergeCandidateCount())
            .param("rejected", 0)
            .param("review", reviewRequired)
            .param("now", now)
            .update();

        // Insert items + create leads for "create" rows
        for (var item : preview.items()) {
            UUID leadId = null;
            if ("create".equals(item.importAction()) && item.matchedListing() != null) {
                leadId = insertLead(advertiserId, UUID.fromString(item.matchedListing().id()),
                    sourceFamily, sourceChannel, item.normalized());
            }

            String extListingRef = item.matchedListing() != null ? item.matchedListing().publicId() : null;
            insertItem(batchId, advertiserId, "lead", sourceFamily, sourceChannel, ingestionMethod,
                item, leadId, null, extListingRef);
        }

        var batch = getBatch(advertiserId, batchId);
        return Map.of("batchId", batchId.toString(), "preview", serializePreview(preview));
    }

    // ── Preview visits (no DB writes) ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public VisitPreviewResponse previewVisits(UUID advertiserId, List<Map<String, Object>> rows) {
        var items = new ArrayList<VisitPreviewItem>();
        int createCount = 0, mergeCount = 0, reviewCount = 0;
        int matchedCount = 0, ambiguousCount = 0, unmatchedCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            var item = previewVisitRow(advertiserId, i, rows.get(i));
            items.add(item);
            switch (item.importAction()) {
                case "create"           -> createCount++;
                case "merge_candidate"  -> mergeCount++;
                default                 -> reviewCount++;
            }
            switch (item.matchStatus()) {
                case "matched"      -> matchedCount++;
                case "ambiguous"    -> ambiguousCount++;
                default             -> unmatchedCount++;
            }
        }

        var summary = new VisitPreviewSummary(rows.size(), createCount, mergeCount, reviewCount,
            matchedCount, ambiguousCount, unmatchedCount);
        return new VisitPreviewResponse(summary, items);
    }

    private VisitPreviewItem previewVisitRow(UUID advertiserId, int rowIndex, Map<String, Object> row) {
        var reasons = new ArrayList<String>();
        var str = (java.util.function.Function<String, String>) key ->
            row.get(key) != null ? row.get(key).toString().trim() : null;

        String publicId = str.apply("listingPublicId");
        String extId    = str.apply("listingExternalId");
        String title    = str.apply("listingTitle");
        String startsAt = str.apply("startsAt");

        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("externalVisitId",   str.apply("externalVisitId"));
        normalized.put("listingPublicId",   publicId);
        normalized.put("listingExternalId", extId);
        normalized.put("listingTitle",      title);
        normalized.put("contactName",       str.apply("contactName"));
        normalized.put("contactEmail",      str.apply("contactEmail"));
        normalized.put("contactPhone",      str.apply("contactPhone"));
        normalized.put("mode",              str.apply("mode") != null ? str.apply("mode") : "in_person");
        normalized.put("status",            str.apply("status") != null ? str.apply("status") : "requested");
        normalized.put("startsAt",          startsAt);
        normalized.put("endsAt",            str.apply("endsAt"));
        normalized.put("meetingUrl",        str.apply("meetingUrl"));
        normalized.put("notes",             str.apply("notes"));

        if (startsAt == null) {
            reasons.add("Data/hora da visita em falta");
            return new VisitPreviewItem(rowIndex, "review_required", "unmatched", reasons,
                normalized, null, null, null);
        }

        MatchedListing listing = findListing(advertiserId, publicId, extId, title);
        if (listing == null) {
            reasons.add("Imóvel não encontrado");
            return new VisitPreviewItem(rowIndex, "review_required", "unmatched", reasons,
                normalized, null, null, null);
        }

        reasons.add("Imóvel identificado");
        return new VisitPreviewItem(rowIndex, "create", "matched", reasons, normalized, listing, null, null);
    }

    // ── Create visit batch ────────────────────────────────────────────────────

    public Map<String, Object> createVisitBatch(UUID advertiserId, UUID createdByUserId,
            String sourceFamily, String sourceChannel, String ingestionMethod,
            String fileName, List<Map<String, Object>> rows) {

        var preview = previewVisits(advertiserId, rows);
        var batchId = UUID.randomUUID();
        var now = java.sql.Timestamp.from(Instant.now());
        var s = preview.summary();

        int reviewRequired = s.reviewRequiredCount();
        String status = reviewRequired == 0 ? "completed" : (s.createCount() > 0 ? "partial" : "processing");

        jdbc.sql("""
                INSERT INTO properia.crm_import_batches
                  (id, advertiser_id, created_by_user_id, entity_type,
                   source_family, source_channel, ingestion_method, status, file_name,
                   total_rows, created_rows, merged_rows, rejected_rows, review_required_rows,
                   metadata, created_at, updated_at)
                VALUES (:id, :adv, :usr, 'visit',
                        :sf::properia.crm_import_source_family,
                        :sc::properia.crm_import_source_channel,
                        :im::properia.crm_import_ingestion_method,
                        :status::properia.crm_import_batch_status,
                        :fn, :total, :created, :merged, :rejected, :review,
                        '{}'::jsonb, :now, :now)
                """)
            .param("id", batchId)
            .param("adv", advertiserId)
            .param("usr", createdByUserId)
            .param("sf", sourceFamily)
            .param("sc", sourceChannel)
            .param("im", ingestionMethod)
            .param("status", status)
            .param("fn", fileName)
            .param("total", s.totalRows())
            .param("created", s.createCount())
            .param("merged", 0)
            .param("rejected", 0)
            .param("review", reviewRequired)
            .param("now", now)
            .update();

        for (var item : preview.items()) {
            if ("create".equals(item.importAction()) && item.matchedListing() != null) {
                insertVisit(advertiserId, UUID.fromString(item.matchedListing().id()),
                    sourceFamily, sourceChannel, item.normalized());
            }
            String extListingRef = item.matchedListing() != null ? item.matchedListing().publicId() : null;
            insertItem(batchId, advertiserId, "visit", sourceFamily, sourceChannel, ingestionMethod,
                item, null, null, extListingRef);
        }

        return Map.of("batchId", batchId.toString(), "preview", serializeVisitPreview(preview));
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ItemDto> listItems(UUID advertiserId, UUID batchId, String matchStatus) {
        return jdbc.sql("""
                SELECT i.id, i.batch_id, i.match_status, i.import_action,
                       i.confidence_score, i.decision_reason, i.error_message,
                       i.normalized_payload::text, i.created_at
                FROM properia.crm_import_items i
                WHERE i.advertiser_id = :adv
                  AND (:batch IS NULL OR i.batch_id = :batch)
                  AND (:ms IS NULL OR i.match_status = :ms::properia.crm_import_match_status)
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private MatchedListing findListing(UUID advertiserId, String publicId, String extId, String title) {
        // 1. By publicId (exact)
        if (publicId != null && !publicId.isBlank()) {
            var result = jdbc.sql("""
                    SELECT id::text, public_id, title, city, district
                    FROM properia.listings
                    WHERE advertiser_id = :adv AND status != 'archived' AND public_id = :pid
                    LIMIT 1
                    """)
                .param("adv", advertiserId)
                .param("pid", publicId)
                .query((rs, n) -> new MatchedListing(rs.getString("id"), rs.getString("public_id"),
                    rs.getString("title"), rs.getString("city"), rs.getString("district")))
                .optional();
            if (result.isPresent()) return result.get();
        }

        // 2. By title (case-insensitive partial match)
        if (title != null && !title.isBlank()) {
            var result = jdbc.sql("""
                    SELECT id::text, public_id, title, city, district
                    FROM properia.listings
                    WHERE advertiser_id = :adv AND status != 'archived'
                      AND lower(title) LIKE lower(:title)
                    ORDER BY created_at DESC
                    LIMIT 1
                    """)
                .param("adv", advertiserId)
                .param("title", "%" + title.replace("%", "\\%") + "%")
                .query((rs, n) -> new MatchedListing(rs.getString("id"), rs.getString("public_id"),
                    rs.getString("title"), rs.getString("city"), rs.getString("district")))
                .optional();
            if (result.isPresent()) return result.get();
        }

        return null;
    }

    private DuplicateLead findDuplicateLead(UUID listingId, String email, String phone) {
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) return null;
        return jdbc.sql("""
                SELECT id::text, created_at, stage::text, contact_name
                FROM properia.leads
                WHERE listing_id = :lid
                  AND ((:email IS NOT NULL AND contact_email = :email)
                    OR (:phone IS NOT NULL AND contact_phone = :phone))
                ORDER BY created_at DESC
                LIMIT 1
                """)
            .param("lid", listingId)
            .param("email", email)
            .param("phone", phone)
            .query((rs, n) -> new DuplicateLead(
                rs.getString("id"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("stage"),
                rs.getString("contact_name")))
            .optional()
            .orElse(null);
    }

    private UUID insertLead(UUID advertiserId, UUID listingId, String sourceFamily,
                            String sourceChannel, Map<String, Object> normalized) {
        // Map sourceFamily:sourceChannel to lead_source enum value
        String source = mapToLeadSource(sourceFamily, sourceChannel);
        var leadId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO properia.leads
                      (id, listing_id, advertiser_id, source, stage, intent_type,
                       message, contact_name, contact_email, contact_phone, metadata, created_at, updated_at)
                    VALUES (:id, :lid, :adv, :src::properia.lead_source, 'new',
                            'buy', :msg, :name, :email, :phone, '{}'::jsonb, :ts, :ts)
                    """)
                .param("id", leadId)
                .param("lid", listingId)
                .param("adv", advertiserId)
                .param("src", source)
                .param("msg", normalized.getOrDefault("message", null))
                .param("name", normalized.getOrDefault("contactName", null))
                .param("email", normalized.getOrDefault("contactEmail", null))
                .param("phone", normalized.getOrDefault("contactPhone", null))
                .param("ts", java.sql.Timestamp.from(Instant.now()))
                .update();
            return leadId;
        } catch (Exception e) {
            return null;
        }
    }

    private void insertVisit(UUID advertiserId, UUID listingId, String sourceFamily,
                             String sourceChannel, Map<String, Object> normalized) {
        try {
            var mode   = normalized.getOrDefault("mode",   "in_person").toString();
            var status = normalized.getOrDefault("status", "requested").toString();
            var starts = normalized.get("startsAt");

            jdbc.sql("""
                    INSERT INTO properia.visits
                      (id, advertiser_id, listing_id, mode, status, starts_at, ends_at,
                       meeting_url, notes, created_at, updated_at)
                    VALUES (:id, :adv, :lid, :mode::properia.visit_mode,
                            :status::properia.visit_status,
                            :starts::timestamptz, :ends::timestamptz,
                            :url, :notes, now(), now())
                    """)
                .param("id", UUID.randomUUID())
                .param("adv", advertiserId)
                .param("lid", listingId)
                .param("mode", mode)
                .param("status", status)
                .param("starts", starts)
                .param("ends", normalized.get("endsAt"))
                .param("url", normalized.get("meetingUrl"))
                .param("notes", normalized.get("notes"))
                .update();
        } catch (Exception ignored) {}
    }

    private void insertItem(UUID batchId, UUID advertiserId, String entityType,
                            String sourceFamily, String sourceChannel, String ingestionMethod,
                            LeadPreviewItem item, UUID leadId, UUID visitId, String extListingRef) {
        try {
            String payload = objectMapper.writeValueAsString(item.normalized());
            jdbc.sql("""
                    INSERT INTO properia.crm_import_items
                      (id, batch_id, advertiser_id, lead_id, source_family, source_channel,
                       ingestion_method, external_listing_ref, match_status, import_action,
                       decision_reason, normalized_payload, raw_payload, metadata, created_at, updated_at)
                    VALUES (:id, :batch, :adv, :lead,
                            :sf::properia.crm_import_source_family,
                            :sc::properia.crm_import_source_channel,
                            :im::properia.crm_import_ingestion_method,
                            :extRef,
                            :ms::properia.crm_import_match_status,
                            :action::properia.crm_import_action,
                            :reason, :payload::jsonb, '{}'::jsonb, '{}'::jsonb, now(), now())
                    """)
                .param("id", UUID.randomUUID())
                .param("batch", batchId)
                .param("adv", advertiserId)
                .param("lead", leadId)
                .param("sf", sourceFamily)
                .param("sc", sourceChannel)
                .param("im", ingestionMethod)
                .param("extRef", extListingRef)
                .param("ms", item.matchStatus())
                .param("action", mapImportAction(item.importAction()))
                .param("reason", String.join("; ", item.reasons()))
                .param("payload", payload)
                .update();
        } catch (Exception ignored) {}
    }

    private void insertItem(UUID batchId, UUID advertiserId, String entityType,
                            String sourceFamily, String sourceChannel, String ingestionMethod,
                            VisitPreviewItem item, UUID leadId, UUID visitId, String extListingRef) {
        try {
            String payload = objectMapper.writeValueAsString(item.normalized());
            jdbc.sql("""
                    INSERT INTO properia.crm_import_items
                      (id, batch_id, advertiser_id, source_family, source_channel,
                       ingestion_method, external_listing_ref, match_status, import_action,
                       decision_reason, normalized_payload, raw_payload, metadata, created_at, updated_at)
                    VALUES (:id, :batch, :adv,
                            :sf::properia.crm_import_source_family,
                            :sc::properia.crm_import_source_channel,
                            :im::properia.crm_import_ingestion_method,
                            :extRef,
                            :ms::properia.crm_import_match_status,
                            :action::properia.crm_import_action,
                            :reason, :payload::jsonb, '{}'::jsonb, '{}'::jsonb, now(), now())
                    """)
                .param("id", UUID.randomUUID())
                .param("batch", batchId)
                .param("adv", advertiserId)
                .param("sf", sourceFamily)
                .param("sc", sourceChannel)
                .param("im", ingestionMethod)
                .param("extRef", extListingRef)
                .param("ms", item.matchStatus())
                .param("action", mapImportAction(item.importAction()))
                .param("reason", String.join("; ", item.reasons()))
                .param("payload", payload)
                .update();
        } catch (Exception ignored) {}
    }

    private String mapToLeadSource(String family, String channel) {
        if ("partner".equals(family)) return "partner_form";
        return "manual";
    }

    private String mapImportAction(String action) {
        return switch (action) {
            case "create"          -> "created";
            case "merge_candidate" -> "merged";
            default                -> "review_required";
        };
    }

    private Map<String, Object> serializePreview(LeadPreviewResponse preview) {
        var s = preview.summary();
        return Map.of(
            "summary", Map.of(
                "totalRows", s.totalRows(), "createCount", s.createCount(),
                "mergeCandidateCount", s.mergeCandidateCount(), "reviewRequiredCount", s.reviewRequiredCount(),
                "matchedCount", s.matchedCount(), "ambiguousCount", s.ambiguousCount(),
                "unmatchedCount", s.unmatchedCount()),
            "items", preview.items().stream().map(i -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("rowIndex", i.rowIndex());
                m.put("importAction", i.importAction());
                m.put("matchStatus", i.matchStatus());
                m.put("reasons", i.reasons());
                m.put("normalized", i.normalized());
                m.put("matchedListing", i.matchedListing());
                m.put("duplicateLead", i.duplicateLead());
                return (Object) m;
            }).toList()
        );
    }

    private Map<String, Object> serializeVisitPreview(VisitPreviewResponse preview) {
        var s = preview.summary();
        return Map.of(
            "summary", Map.of(
                "totalRows", s.totalRows(), "createCount", s.createCount(),
                "mergeCandidateCount", s.mergeCandidateCount(), "reviewRequiredCount", s.reviewRequiredCount(),
                "matchedCount", s.matchedCount(), "ambiguousCount", s.ambiguousCount(),
                "unmatchedCount", s.unmatchedCount()),
            "items", preview.items().stream().map(i -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("rowIndex", i.rowIndex());
                m.put("importAction", i.importAction());
                m.put("matchStatus", i.matchStatus());
                m.put("reasons", i.reasons());
                m.put("normalized", i.normalized());
                m.put("matchedListing", i.matchedListing());
                m.put("matchedLead", i.matchedLead());
                m.put("duplicateVisit", i.duplicateVisit());
                return (Object) m;
            }).toList()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }
}
