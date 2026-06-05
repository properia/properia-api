package pt.properia.api.modules.listings.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.listings.infrastructure.ListingMediaJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class VirtualTourService {

    private static final Logger log = LoggerFactory.getLogger(VirtualTourService.class);

    private final ListingRepository listingRepository;
    private final ListingMediaJpaRepository mediaRepo;
    private final KlingService kling;
    private final FfmpegService ffmpeg;
    private final JdbcClient jdbc;

    @Value("${properia.app.url:https://properia.pt}")
    private String appUrl;

    public VirtualTourService(
            ListingRepository listingRepository,
            ListingMediaJpaRepository mediaRepo,
            KlingService kling,
            FfmpegService ffmpeg,
            JdbcClient jdbc) {
        this.listingRepository = listingRepository;
        this.mediaRepo         = mediaRepo;
        this.kling             = kling;
        this.ffmpeg            = ffmpeg;
        this.jdbc              = jdbc;
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    @Transactional
    public void requestGeneration(UUID listingId, UUID advertiserId) {
        listingRepository.findByIdAndAdvertiserId(listingId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        var photos = mediaRepo.findByListingIdOrderBySortOrderAsc(listingId).stream()
            .filter(m -> "image".equals(m.getMediaType()))
            .map(m -> {
                var url = m.getUrl();
                return (url != null && url.startsWith("/")) ? appUrl + url : url;
            })
            .limit(20)
            .toList();

        if (photos.size() < 2) {
            throw new DomainException("INSUFFICIENT_PHOTOS",
                "São necessárias pelo menos 2 fotos para gerar o tour virtual.", 422);
        }

        upsertCommercial(listingId, "pending", null, null, null);
        submitAsync(listingId, photos);
    }

    @Async
    public void submitAsync(UUID listingId, List<String> photos) {
        try {
            upsertCommercial(listingId, "processing", null, null, null);

            // Submit all clips in parallel (one per photo, max 5)
            var submissions = kling.submitAllClips(photos);
            log.info("Kling: {} clips submitted for listing {}", submissions.size(), listingId);

            // Poll all clips until complete
            var clipUrls = submissions.stream()
                .map(s -> kling.pollUntilComplete(s.requestId()))
                .toList();

            String finalVideoUrl;
            if (clipUrls.size() == 1) {
                // Single clip — use directly, no concat needed
                finalVideoUrl = clipUrls.get(0);
            } else {
                // Multiple clips — concatenate with ffmpeg
                log.info("Concatenating {} clips for listing {}", clipUrls.size(), listingId);
                var stitchedPath = ffmpeg.concatenate(clipUrls);
                // Store the stitched video — serve via local storage in dev
                finalVideoUrl = storeVideo(listingId, stitchedPath);
            }

            upsertCommercial(listingId, "ready", null, finalVideoUrl, Instant.now());
            log.info("Virtual tour ready for listing {} — {}", listingId, finalVideoUrl);

        } catch (Exception e) {
            log.error("Failed to generate virtual tour for listing {}", listingId, e);
            upsertCommercial(listingId, "error", null, null, null);
        }
    }

    /**
     * Stores the stitched video using the same local-storage path as media uploads.
     * Served via GET /api/local-storage/media/tours/{listingId}-tour.mp4
     */
    private String storeVideo(UUID listingId, java.nio.file.Path videoPath) throws Exception {
        // Uses the same tmpdir as MediaController (java.io.tmpdir) so the file is served correctly
        var baseDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "properia-uploads", "tours");
        java.nio.file.Files.createDirectories(baseDir);
        var fileName = listingId + "-tour.mp4";
        var dest = baseDir.resolve(fileName);
        java.nio.file.Files.copy(videoPath, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.deleteIfExists(videoPath);
        return appUrl + "/api/local-storage/media/tours/" + fileName;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TourStatus getStatus(UUID listingId, UUID advertiserId) {
        listingRepository.findByIdAndAdvertiserId(listingId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        return jdbc.sql("""
            SELECT virtual_tour_status, virtual_tour_url, virtual_tour_generated_at
            FROM properia.listing_commercial
            WHERE listing_id = :lid
            """)
            .param("lid", listingId)
            .query((rs, n) -> new TourStatus(
                rs.getString("virtual_tour_status"),
                rs.getString("virtual_tour_url"),
                rs.getTimestamp("virtual_tour_generated_at") != null
                    ? rs.getTimestamp("virtual_tour_generated_at").toInstant().toString()
                    : null
            ))
            .optional()
            .orElse(new TourStatus(null, null, null));
    }

    public record TourStatus(String status, String url, String generatedAt) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void upsertCommercial(UUID listingId, String status, String renderId,
                                   String url, Instant generatedAt) {
        jdbc.sql("""
            INSERT INTO properia.listing_commercial
              (listing_id, exclusive_listing, online_visit_available, visit_booking_enabled,
               show_phone, show_chat,
               virtual_tour_status, virtual_tour_render_id,
               virtual_tour_url, virtual_tour_generated_at, updated_at)
            VALUES
              (:lid, false, false, true, true, true,
               :status, :rid, :url, :gen, now())
            ON CONFLICT (listing_id) DO UPDATE SET
              virtual_tour_status       = COALESCE(:status, listing_commercial.virtual_tour_status),
              virtual_tour_render_id    = COALESCE(:rid,    listing_commercial.virtual_tour_render_id),
              virtual_tour_url          = COALESCE(:url,    listing_commercial.virtual_tour_url),
              virtual_tour_generated_at = COALESCE(:gen,    listing_commercial.virtual_tour_generated_at),
              updated_at                = now()
            """)
            .param("lid",    listingId)
            .param("status", status)
            .param("rid",    renderId)
            .param("url",    url)
            .param("gen",    generatedAt != null ? java.sql.Timestamp.from(generatedAt) : null)
            .update();
    }
}
