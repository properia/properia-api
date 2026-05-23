package pt.properia.api.modules.media.interfaces;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final JdbcClient jdbc;

    @Value("${properia.storage.bucket-url:}")
    private String bucketUrl;

    public MediaController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * POST /api/media/upload-sessions
     * Creates a pending upload session and returns an upload URL.
     * When no external storage is configured, returns a local stub URL.
     */
    @PostMapping("/upload-sessions")
    public ResponseEntity<?> createUploadSession(@RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);

        var listingId = body.get("listingId") != null ? UUID.fromString(body.get("listingId").toString()) : null;
        var fileName = body.getOrDefault("fileName", "image.jpg").toString();
        var contentType = body.getOrDefault("contentType", "image/jpeg").toString();
        var sessionId = UUID.randomUUID();
        var objectKey = buildObjectKey(listingId, sessionId, fileName);
        var expiresAt = Instant.now().plusSeconds(900); // 15 min

        jdbc.sql("""
                INSERT INTO properia.media_upload_sessions
                  (id, user_id, listing_id, object_key, content_type, file_name, status, expires_at, created_at, updated_at)
                VALUES (:id, :uid, :lid, :key, :ct, :fn, 'pending', :exp, now(), now())
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", sessionId)
            .param("uid", claims.userId())
            .param("lid", listingId)
            .param("key", objectKey)
            .param("ct", contentType)
            .param("fn", fileName)
            .param("exp", java.sql.Timestamp.from(expiresAt))
            .update();

        var uploadUrl = buildUploadUrl(objectKey);
        var publicUrl = buildPublicUrl(objectKey);

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "sessionId", sessionId.toString(),
            "uploadUrl", uploadUrl,
            "publicUrl", publicUrl,
            "objectKey", objectKey,
            "method", "PUT",
            "expiresAt", expiresAt.toString()
        )));
    }

    /**
     * POST /api/media/confirm
     * Confirms a completed upload and creates the listing_media record.
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirmUpload(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);

        var sessionId = UUID.fromString(body.get("sessionId").toString());

        var session = jdbc.sql("""
                SELECT id, listing_id, object_key, content_type, file_name, status
                FROM properia.media_upload_sessions
                WHERE id = :id AND user_id = :uid
                """)
            .param("id", sessionId)
            .param("uid", claims.userId())
            .query((rs, n) -> Map.of(
                "id", rs.getString("id"),
                "listingId", Optional.ofNullable(rs.getString("listing_id")),
                "objectKey", rs.getString("object_key"),
                "contentType", rs.getString("content_type"),
                "fileName", Optional.ofNullable(rs.getString("file_name")).orElse(""),
                "status", rs.getString("status")
            ))
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Sessão de upload não encontrada.", 404));

        if ("confirmed".equals(session.get("status"))) {
            return ResponseEntity.ok(Map.of("data", Map.of("alreadyConfirmed", true)));
        }

        var objectKey = (String) session.get("objectKey");
        var publicUrl = buildPublicUrl(objectKey);
        var mediaId = UUID.randomUUID();

        @SuppressWarnings("unchecked")
        var listingIdOpt = (Optional<String>) session.get("listingId");
        var listingId = listingIdOpt.isPresent() && listingIdOpt.get() != null
            ? UUID.fromString(listingIdOpt.get()) : null;

        if (listingId != null) {
            var displayOrder = jdbc.sql("""
                    SELECT COALESCE(MAX(sort_order), 0) + 1
                    FROM properia.listing_media WHERE listing_id = :lid
                    """).param("lid", listingId).query(Integer.class).single();

            jdbc.sql("""
                    INSERT INTO properia.listing_media
                      (id, listing_id, url, media_type, source_type, sort_order, file_name, is_cover, created_at, updated_at)
                    VALUES (:id, :lid, :url, 'image', 'manual', :order, :fn, false, now(), now())
                    """)
                .param("id", mediaId)
                .param("lid", listingId)
                .param("url", publicUrl)
                .param("order", displayOrder)
                .param("fn", session.get("fileName"))
                .update();
        }

        jdbc.sql("""
                UPDATE properia.media_upload_sessions SET status = 'confirmed', updated_at = now()
                WHERE id = :id
                """).param("id", sessionId).update();

        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", mediaId.toString(),
            "url", publicUrl,
            "listingId", listingId != null ? listingId.toString() : (Object) null
        )));
    }

    private String buildObjectKey(UUID listingId, UUID sessionId, String fileName) {
        var prefix = listingId != null ? "listings/" + listingId : "uploads";
        var safe = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return prefix + "/" + sessionId + "-" + safe;
    }

    private String buildUploadUrl(String objectKey) {
        if (bucketUrl != null && !bucketUrl.isBlank()) {
            return bucketUrl + "/" + objectKey + "?upload=1";
        }
        return "/api/local-storage/upload/" + objectKey;
    }

    private String buildPublicUrl(String objectKey) {
        if (bucketUrl != null && !bucketUrl.isBlank()) {
            return bucketUrl + "/" + objectKey;
        }
        return "/api/local-storage/media/" + objectKey;
    }
}
