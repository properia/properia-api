package pt.properia.api.modules.listings.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.modules.zone.application.ZoneSnapshotService;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PublishListingUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishListingUseCase.class);

    private final ListingRepository repository;
    private final ZoneSnapshotService zoneSnapshotService;
    private final JdbcClient jdbc;
    private final ListingPublishReadinessValidator readinessValidator;

    public PublishListingUseCase(ListingRepository repository,
                                  ZoneSnapshotService zoneSnapshotService,
                                  JdbcClient jdbc,
                                  ListingPublishReadinessValidator readinessValidator) {
        this.repository          = repository;
        this.zoneSnapshotService = zoneSnapshotService;
        this.jdbc                = jdbc;
        this.readinessValidator  = readinessValidator;
    }

    public record Command(UUID listingId, UUID advertiserId) {}

    public Listing execute(Command cmd) {
        var listing = repository.findByIdAndAdvertiserId(cmd.listingId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        if ("archived".equals(listing.getStatus())) {
            throw new DomainException("INVALID_STATUS", "Um anúncio arquivado não pode ser publicado.");
        }

        readinessValidator.assertReadyToPublish(listing);

        var now = Instant.now();
        var isFirstPublish = listing.getFirstPublishedAt() == null;
        listing.setStatus("published");
        listing.setPublishedAt(now);
        if (isFirstPublish) listing.setFirstPublishedAt(now);

        var saved = repository.save(listing);

        // Auto-trigger zone enrichment on first publish if coordinates are available.
        // Re-publish (paused → published) does not re-trigger to avoid redundant processing.
        if (isFirstPublish && saved.getLatitude() != null && saved.getLongitude() != null) {
            var loc = fetchLocationForZone(saved.getId());
            log.info("Auto-triggering zone enrichment for listing {} on first publish", saved.getId());
            zoneSnapshotService.processAsync(
                saved.getId(),
                saved.getLatitude(),
                saved.getLongitude(),
                loc.get("street"),
                loc.get("neighborhood"),
                saved.getCity(),
                loc.get("precision")
            );
        }

        return saved;
    }

    private Map<String, String> fetchLocationForZone(UUID listingId) {
        return jdbc.sql("""
                SELECT street, neighborhood, location_precision AS precision
                FROM properia.listing_location WHERE listing_id = :lid
                """)
            .param("lid", listingId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, String>();
                m.put("street",       rs.getString("street"));
                m.put("neighborhood", rs.getString("neighborhood"));
                m.put("precision",    rs.getString("precision"));
                return m;
            })
            .optional()
            .orElseGet(LinkedHashMap::new);
    }
}
