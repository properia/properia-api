package pt.properia.api.modules.media.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@RestController
public class MediaController {

    private final JdbcClient jdbc;
    private final Path localStorageDir;

    @Value("${properia.storage.bucket-url:}")
    private String bucketUrl;

    public MediaController(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.localStorageDir = Paths.get(System.getProperty("java.io.tmpdir"), "properia-uploads");
        try {
            Files.createDirectories(localStorageDir);
        } catch (IOException ignored) {}
    }

    /**
     * POST /api/media/upload-sessions
     * Creates a pending upload session and returns an upload URL.
     * Returns thumbnailUrl (null) and headers ({}) so the frontend contract is satisfied.
     */
    @PostMapping("/api/media/upload-sessions")
    public ResponseEntity<?> createUploadSession(@RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);

        var listingId = body.get("listingId") != null ? UUID.fromString(body.get("listingId").toString()) : null;
        var fileName = body.getOrDefault("fileName", "image.jpg").toString();
        var contentType = body.getOrDefault("contentType", "image/jpeg").toString();
        var sessionId = UUID.randomUUID();
        var objectKey = buildObjectKey(listingId, sessionId, fileName);
        var expiresAt = Instant.now().plusSeconds(900);

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

        var data = new LinkedHashMap<String, Object>();
        data.put("sessionId", sessionId.toString());
        data.put("uploadUrl", uploadUrl);
        data.put("publicUrl", publicUrl);
        data.put("objectKey", objectKey);
        data.put("method", "PUT");
        data.put("expiresAt", expiresAt.toString());
        data.put("thumbnailUrl", null);
        data.put("headers", Collections.emptyMap());

        return ResponseEntity.status(201).body(Map.of("data", data));
    }

    /**
     * POST /api/media/confirm
     * Looks up the session by objectKey (which the frontend sends), inserts the
     * listing_media record, and returns the shape the frontend contract expects.
     */
    @PostMapping("/api/media/confirm")
    public ResponseEntity<?> confirmUpload(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);

        var objectKey = body.get("objectKey") != null ? body.get("objectKey").toString() : null;
        if (objectKey == null || objectKey.isBlank()) {
            throw new DomainException("BAD_REQUEST", "objectKey é obrigatório.", 400);
        }

        var session = jdbc.sql("""
                SELECT id, listing_id, object_key, content_type, file_name, status
                FROM properia.media_upload_sessions
                WHERE object_key = :key AND user_id = :uid
                """)
            .param("key", objectKey)
            .param("uid", claims.userId())
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("listingId", Optional.ofNullable(rs.getString("listing_id")));
                m.put("objectKey", rs.getString("object_key"));
                m.put("fileName", Optional.ofNullable(rs.getString("file_name")).orElse(""));
                m.put("status", rs.getString("status"));
                return m;
            })
            .optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Sessão de upload não encontrada.", 404));

        if ("confirmed".equals(session.get("status"))) {
            return ResponseEntity.ok(Map.of("data", Map.of("alreadyConfirmed", true)));
        }

        var sessionId = UUID.fromString((String) session.get("id"));
        var publicUrl = body.get("publicUrl") != null ? body.get("publicUrl").toString() : buildPublicUrl(objectKey);
        var mediaType = body.getOrDefault("mediaType", "image").toString();
        var roomHint = body.getOrDefault("roomHint", "other").toString();
        var isCover = Boolean.TRUE.equals(body.get("isCover"));
        var mediaId = UUID.randomUUID();

        @SuppressWarnings("unchecked")
        var listingIdOpt = (Optional<String>) session.get("listingId");
        var listingId = listingIdOpt.isPresent() && listingIdOpt.get() != null
            ? UUID.fromString(listingIdOpt.get()) : null;

        int sortOrder = 1;
        if (listingId != null) {
            sortOrder = jdbc.sql("""
                    SELECT COALESCE(MAX(sort_order), 0) + 1
                    FROM properia.listing_media WHERE listing_id = :lid
                    """).param("lid", listingId).query(Integer.class).single();

            jdbc.sql("""
                    INSERT INTO properia.listing_media
                      (id, listing_id, url, media_type, source_type, sort_order, file_name,
                       is_cover, room_hint, created_at, updated_at)
                    VALUES (:id, :lid, :url, CAST(:type AS properia.media_type), 'upload',
                            :order, :fn, :cover, CAST(:hint AS properia.room_hint), now(), now())
                    """)
                .param("id", mediaId)
                .param("lid", listingId)
                .param("url", publicUrl)
                .param("type", mediaType)
                .param("order", sortOrder)
                .param("fn", session.get("fileName"))
                .param("cover", isCover)
                .param("hint", roomHint)
                .update();
        }

        jdbc.sql("""
                UPDATE properia.media_upload_sessions SET status = 'confirmed', updated_at = now()
                WHERE id = :id
                """).param("id", sessionId).update();

        var responseData = new LinkedHashMap<String, Object>();
        responseData.put("mediaId", mediaId.toString());
        responseData.put("url", publicUrl);
        responseData.put("listingId", listingId != null ? listingId.toString() : null);
        responseData.put("thumbnailUrl", null);
        responseData.put("sortOrder", sortOrder);
        responseData.put("isCover", isCover);

        return ResponseEntity.status(201).body(Map.of("data", responseData));
    }

    /**
     * PUT /api/local-storage/upload/**
     * Dev-only: stores the raw upload bytes to the local filesystem.
     * Used when no external bucket is configured (properia.storage.bucket-url is blank).
     */
    @PutMapping("/api/local-storage/upload/**")
    public ResponseEntity<Void> storeLocalFile(HttpServletRequest request) throws IOException {
        var objectKey = extractSuffix(request.getRequestURI(), "/api/local-storage/upload/");
        var target = resolveLocalPath(objectKey);
        Files.createDirectories(target.getParent());
        try (var in = request.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/local-storage/media/**
     * Dev-only: serves files stored by storeLocalFile.
     */
    @GetMapping("/api/local-storage/media/**")
    public ResponseEntity<byte[]> serveLocalFile(HttpServletRequest request) throws IOException {
        var objectKey = extractSuffix(request.getRequestURI(), "/api/local-storage/media/");
        var target = resolveLocalPath(objectKey);
        if (!Files.exists(target)) return ResponseEntity.notFound().build();
        var bytes = Files.readAllBytes(target);
        var contentType = Files.probeContentType(target);
        return ResponseEntity.ok()
            .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes);
    }

    /**
     * POST /api/advertiser/listings/{listingId}/media/upload
     * Server-side multipart fallback used when direct browser-to-storage upload is unavailable.
     */
    @PostMapping("/api/advertiser/listings/{listingId}/media/upload")
    public ResponseEntity<?> uploadMediaFallback(
            @PathVariable UUID listingId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mediaType", defaultValue = "image") String mediaType,
            @RequestParam(value = "roomHint", defaultValue = "other") String roomHint,
            @AuthenticationPrincipal JwtClaims claims) throws IOException {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        var advertiserId = requireAdvertiserId(claims);

        jdbc.sql("SELECT 1 FROM properia.listings WHERE id = :id AND advertiser_id = :adv")
            .param("id", listingId).param("adv", advertiserId)
            .query((rs, n) -> 1).optional()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Imóvel não encontrado.", 404));

        var sessionId = UUID.randomUUID();
        var safeName = file.getOriginalFilename() != null
            ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_") : "upload.bin";
        var objectKey = "listings/" + listingId + "/" + sessionId + "-" + safeName;
        var publicUrl = buildPublicUrl(objectKey);

        var target = resolveLocalPath(objectKey);
        Files.createDirectories(target.getParent());
        file.transferTo(target);

        var mediaId = UUID.randomUUID();
        var sortOrder = jdbc.sql("""
                SELECT COALESCE(MAX(sort_order), 0) + 1
                FROM properia.listing_media WHERE listing_id = :lid
                """).param("lid", listingId).query(Integer.class).single();

        jdbc.sql("""
                INSERT INTO properia.listing_media
                  (id, listing_id, url, media_type, source_type, sort_order, file_name,
                   is_cover, room_hint, created_at, updated_at)
                VALUES (:id, :lid, :url, CAST(:type AS properia.media_type), 'upload',
                        :order, :fn, false, CAST(:hint AS properia.room_hint), now(), now())
                """)
            .param("id", mediaId)
            .param("lid", listingId)
            .param("url", publicUrl)
            .param("type", mediaType)
            .param("order", sortOrder)
            .param("fn", safeName)
            .param("hint", roomHint)
            .update();

        var responseData = new LinkedHashMap<String, Object>();
        responseData.put("mediaId", mediaId.toString());
        responseData.put("url", publicUrl);
        responseData.put("listingId", listingId.toString());
        responseData.put("thumbnailUrl", null);
        responseData.put("sortOrder", sortOrder);
        responseData.put("isCover", false);

        return ResponseEntity.status(201).body(Map.of("data", responseData));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private Path resolveLocalPath(String objectKey) {
        var normalized = Paths.get(objectKey).normalize();
        if (normalized.isAbsolute() || normalized.toString().startsWith("..")) {
            throw new DomainException("BAD_REQUEST", "Caminho de objecto inválido.", 400);
        }
        return localStorageDir.resolve(normalized).normalize();
    }

    private String extractSuffix(String uri, String prefix) {
        int idx = uri.indexOf(prefix);
        return idx >= 0 ? uri.substring(idx + prefix.length()) : "unknown";
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
