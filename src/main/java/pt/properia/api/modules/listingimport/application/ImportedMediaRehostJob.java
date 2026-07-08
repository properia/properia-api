package pt.properia.api.modules.listingimport.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.properia.api.modules.media.infrastructure.R2UploadService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Fase B do importador de imóveis: traz para o R2 as imagens que entraram a
 * apontar para o CDN externo do CRM/portal (source_type='external',
 * metadata.importPending=true).
 *
 * Faz o download dos bytes, faz upload para o nosso bucket e troca o URL pelo
 * permanente — deixando de depender do CDN da agência. Corre em background em
 * lotes pequenos para não competir com o tráfego normal nem estourar timeouts.
 *
 * Degradação graciosa: se uma imagem falhar repetidamente, deixa de ser tentada
 * mas mantém o URL externo (hotlink) — pior é ter foto do que não ter nenhuma.
 */
@Component
public class ImportedMediaRehostJob {

    private static final Logger log = LoggerFactory.getLogger(ImportedMediaRehostJob.class);

    private static final int BATCH_SIZE = 15;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_BYTES = 15L * 1024 * 1024; // 15 MB por imagem

    private final JdbcClient jdbc;
    private final R2UploadService r2;
    private final HttpClient http;

    public ImportedMediaRehostJob(JdbcClient jdbc, R2UploadService r2) {
        this.jdbc = jdbc;
        this.r2 = r2;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Scheduled(fixedDelay = 2 * 60 * 1000L, initialDelay = 90 * 1000L)
    public void rehostPendingImages() {
        if (!r2.isConfigured()) return; // Sem bucket: mantém o hotlink, nada a fazer.

        var pending = jdbc.sql("""
                SELECT id, listing_id, url,
                       COALESCE((metadata->>'importAttempts')::int, 0) AS attempts
                FROM properia.listing_media
                WHERE source_type = 'external'
                  AND media_type = 'image'
                  AND (metadata->>'importPending') = 'true'
                ORDER BY created_at ASC
                LIMIT :limit
                """)
            .param("limit", BATCH_SIZE)
            .query((rs, n) -> new PendingMedia(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("listing_id")),
                rs.getString("url"),
                rs.getInt("attempts")))
            .list();

        if (pending.isEmpty()) return;

        int ok = 0, failed = 0;
        for (var media : pending) {
            try {
                rehostOne(media);
                ok++;
            } catch (Exception e) {
                markFailure(media, e.getMessage());
                failed++;
            }
        }
        log.info("ImportedMediaRehostJob: {} imagem(ns) migrada(s) para o R2, {} falha(s).", ok, failed);
    }

    private void rehostOne(PendingMedia media) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(media.url()))
            .header("User-Agent", "Properia-ImportRehost/1.0")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        var bytes = response.body();
        if (bytes == null || bytes.length == 0) throw new RuntimeException("empty body");
        if (bytes.length > MAX_BYTES) throw new RuntimeException("too large: " + bytes.length);

        var contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg").split(";")[0].trim();
        if (!contentType.startsWith("image/")) contentType = "image/jpeg";

        var objectKey = "imports/" + media.listingId() + "/" + media.id() + "." + extensionFor(contentType);
        var publicUrl = r2.uploadBytes(objectKey, bytes, contentType);

        jdbc.sql("""
                UPDATE properia.listing_media
                SET url = :url,
                    storage_key = :key,
                    mime_type = :ct,
                    source_type = 'upload',
                    metadata = (metadata - 'importPending' - 'importAttempts' - 'importError'),
                    updated_at = now()
                WHERE id = :id
                """)
            .param("url", publicUrl)
            .param("key", objectKey)
            .param("ct", contentType)
            .param("id", media.id())
            .update();

        // Se era a capa, aponta o hero para o URL permanente.
        jdbc.sql("""
                UPDATE properia.listings l
                SET hero_image_url = :url, updated_at = now()
                FROM properia.listing_media m
                WHERE m.id = :id AND m.is_cover = true AND l.id = m.listing_id
                """)
            .param("url", publicUrl)
            .param("id", media.id())
            .update();
    }

    private void markFailure(PendingMedia media, String error) {
        var attempts = media.attempts() + 1;
        var keepTrying = attempts < MAX_ATTEMPTS;
        var cleaned = error == null ? "unknown" : error.replace("'", "");
        var safeError = cleaned.substring(0, Math.min(cleaned.length(), 200));
        // Constrói o novo metadata: incrementa tentativas, guarda o erro, e só
        // mantém importPending=true enquanto não esgotámos as tentativas.
        jdbc.sql("""
                UPDATE properia.listing_media
                SET metadata = jsonb_set(
                                 jsonb_set(
                                   jsonb_set(metadata, '{importAttempts}', to_jsonb(:attempts)),
                                   '{importError}', to_jsonb(CAST(:err AS text))),
                                 '{importPending}', to_jsonb(:pending)),
                    updated_at = now()
                WHERE id = :id
                """)
            .param("attempts", attempts)
            .param("err", safeError)
            .param("pending", keepTrying ? "true" : "false")
            .param("id", media.id())
            .update();
        if (!keepTrying) {
            log.warn("ImportedMediaRehostJob: imagem {} desistida após {} tentativas (mantém hotlink). Último erro: {}",
                media.id(), attempts, safeError);
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> "jpg";
        };
    }

    private record PendingMedia(UUID id, UUID listingId, String url, int attempts) {}
}
