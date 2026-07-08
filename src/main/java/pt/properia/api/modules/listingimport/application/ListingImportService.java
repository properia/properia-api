package pt.properia.api.modules.listingimport.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.application.CreateListingUseCase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orquestra a importação de inventário de imóveis:
 * analyze → mapeia (IA) + normaliza + dedup → preview; commit → cria rascunhos.
 *
 * Nunca publica: os imóveis entram como "draft" e passam pelos checks obrigatórios
 * de publicação já existentes antes de irem para o ar.
 */
@Service
public class ListingImportService {

    private static final Logger log = LoggerFactory.getLogger(ListingImportService.class);

    private final ListingImportParser parser;
    private final ListingImportMappingService mappingService;
    private final ListingImportNormalizer norm;
    private final CreateListingUseCase createListing;
    private final JdbcClient jdbc;

    public ListingImportService(ListingImportParser parser,
                                ListingImportMappingService mappingService,
                                ListingImportNormalizer norm,
                                CreateListingUseCase createListing,
                                JdbcClient jdbc) {
        this.parser = parser;
        this.mappingService = mappingService;
        this.norm = norm;
        this.createListing = createListing;
        this.jdbc = jdbc;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /** Campos normalizados de um imóvel importado. */
    public record NormalizedListing(
        String externalRef, String businessType, String propertyType, String title,
        String description, BigDecimal price, Integer bedrooms, BigDecimal bathrooms,
        Integer garageSpaces, Integer parkingSpaces,
        BigDecimal usableAreaM2, BigDecimal grossAreaM2, BigDecimal lotAreaM2,
        String city, String district, String municipality, String parish,
        String neighborhood, String street, String postalCode,
        Double latitude, Double longitude, String condition, String furnished, String energyRating
    ) {}

    public record PreviewItem(
        int rowIndex, NormalizedListing normalized, double confidence,
        String action, List<String> reasons, String duplicateOfPublicId,
        int imageCount, String coverImageUrl
    ) {}

    public record MappingRow(String field, String sourceColumn, double confidence) {}

    public record AnalyzeResult(
        String detectedSource, boolean usedAi, int totalRows,
        List<MappingRow> mapping, List<PreviewItem> items,
        int readyCount, int reviewCount, int duplicateCount
    ) {}

    public record CreatedListing(String id, String publicId, String title) {}

    public record CommitResult(int createdCount, int skippedCount, List<CreatedListing> listings) {}

    // ── Analyze ────────────────────────────────────────────────────────────────

    public AnalyzeResult analyze(UUID advertiserId, String fileName, String content) {
        var parsed = parser.parse(fileName, content);
        var mappingResult = mappingService.buildMapping(parsed.columns(), parsed.records());
        var existingTitles = loadExistingTitles(advertiserId);

        var mappingRows = new ArrayList<MappingRow>();
        mappingResult.mapping().forEach((field, col) ->
            mappingRows.add(new MappingRow(field, col, mappingResult.confidence().getOrDefault(field, 0.6))));

        var seenInBatch = new HashSet<String>();
        var items = new ArrayList<PreviewItem>();
        int ready = 0, review = 0, duplicate = 0;

        for (int i = 0; i < parsed.records().size(); i++) {
            var normalized = normalizeRow(parsed.records().get(i), mappingResult.mapping());
            var reasons = new ArrayList<String>();

            // Campos obrigatórios para sequer criar um rascunho.
            if (isBlank(normalized.title())) reasons.add("missing_title");
            if (normalized.propertyType() == null) reasons.add("missing_property_type");
            if (normalized.businessType() == null) reasons.add("missing_business_type");
            if (normalized.price() == null) reasons.add("missing_price");
            if (normalized.usableAreaM2() == null && normalized.grossAreaM2() == null
                && normalized.lotAreaM2() == null) reasons.add("missing_area");
            if (isBlank(normalized.city()) && isBlank(normalized.district())
                && isBlank(normalized.parish())) reasons.add("missing_location");

            var dupKey = duplicateKey(normalized);
            String dupPublicId = null;
            if (dupKey != null) {
                if (existingTitles.containsKey(dupKey)) dupPublicId = existingTitles.get(dupKey);
                else if (!seenInBatch.add(dupKey)) reasons.add("duplicate_in_file");
            }

            String action;
            if (dupPublicId != null) { action = "duplicate"; duplicate++; }
            else if (reasons.contains("missing_title") || reasons.contains("missing_property_type")
                || reasons.contains("missing_business_type")) { action = "review"; review++; }
            else if (!reasons.isEmpty()) { action = "review"; review++; }
            else { action = "ready"; ready++; }

            var confidence = rowConfidence(normalized, reasons);
            var images = i < parsed.imagesByRow().size() ? parsed.imagesByRow().get(i) : List.<String>of();
            var cover = images.isEmpty() ? null : images.get(0);
            items.add(new PreviewItem(i, normalized, confidence, action, reasons, dupPublicId, images.size(), cover));
        }

        return new AnalyzeResult(
            mappingResult.detectedSource(), mappingResult.usedAi(), parsed.records().size(),
            mappingRows, items, ready, review, duplicate
        );
    }

    // ── Commit ───────────────────────────────────────────────────────────────

    public CommitResult commit(UUID advertiserId, UUID ownerUserId, String fileName, String content,
                               Set<Integer> excludeRowIndices) {
        var parsed = parser.parse(fileName, content);
        var mappingResult = mappingService.buildMapping(parsed.columns(), parsed.records());
        var existingTitles = loadExistingTitles(advertiserId);
        var seenInBatch = new HashSet<String>();

        var created = new ArrayList<CreatedListing>();
        int skipped = 0;

        for (int i = 0; i < parsed.records().size(); i++) {
            if (excludeRowIndices != null && excludeRowIndices.contains(i)) { skipped++; continue; }
            var n = normalizeRow(parsed.records().get(i), mappingResult.mapping());

            // Guarda de servidor: não cria rascunhos sem os obrigatórios nem duplicados.
            if (isBlank(n.title()) || n.propertyType() == null || n.businessType() == null) { skipped++; continue; }
            var dupKey = duplicateKey(n);
            if (dupKey != null && (existingTitles.containsKey(dupKey) || !seenInBatch.add(dupKey))) { skipped++; continue; }

            try {
                var listing = createListing.execute(new CreateListingUseCase.Command(
                    advertiserId, ownerUserId, n.businessType(), n.propertyType(), null,
                    n.title(), n.description(), null, n.price(),
                    n.bedrooms(), n.bathrooms(), null, n.garageSpaces(), n.parkingSpaces(),
                    n.usableAreaM2(), n.grossAreaM2(), n.lotAreaM2(), null, null, null, null,
                    n.city(), n.district(), n.municipality(), n.parish(), n.neighborhood(),
                    n.street(), n.postalCode(), n.latitude(), n.longitude(), null,
                    n.condition(), n.furnished(), n.energyRating(), null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null
                ));
                var images = i < parsed.imagesByRow().size() ? parsed.imagesByRow().get(i) : List.<String>of();
                insertImportedMedia(listing.getId(), images);
                created.add(new CreatedListing(
                    listing.getId().toString(), listing.getPublicId(), listing.getTitle()));
            } catch (Exception e) {
                log.warn("Import: failed to create draft from row {}: {}", i, e.getMessage());
                skipped++;
            }
        }
        return new CommitResult(created.size(), skipped, created);
    }

    // ── Media importada (Fase A) ─────────────────────────────────────────────

    private static final int MAX_IMAGES_PER_LISTING = 40;

    /**
     * Insere as imagens do feed como media "external" a apontar para o URL de
     * origem — ficam visíveis de imediato. A flag metadata.importPending=true
     * sinaliza o worker de rehost para as trazer para o R2 depois (Fase B).
     */
    private void insertImportedMedia(UUID listingId, List<String> imageUrls) {
        int sort = 0;
        for (var url : imageUrls) {
            if (sort >= MAX_IMAGES_PER_LISTING) break;
            if (url == null || url.isBlank()) continue;
            var isCover = sort == 0;
            sort++;
            jdbc.sql("""
                    INSERT INTO properia.listing_media
                      (listing_id, media_type, source_type, url, sort_order, is_cover, metadata, created_at, updated_at)
                    VALUES
                      (:lid, 'image', 'external', :url, :sort, :cover,
                       '{"importPending":"true"}'::jsonb, now(), now())
                    """)
                .param("lid", listingId)
                .param("url", url.trim())
                .param("sort", sort)
                .param("cover", isCover)
                .update();
            if (isCover) {
                jdbc.sql("UPDATE properia.listings SET hero_image_url = :url, updated_at = now() WHERE id = :lid")
                    .param("url", url.trim()).param("lid", listingId).update();
            }
        }
    }

    // ── Normalização de uma linha ────────────────────────────────────────────

    private NormalizedListing normalizeRow(Map<String, String> record, Map<String, String> mapping) {
        var typology = get(record, mapping, "typology");
        var bedrooms = norm.integer(get(record, mapping, "bedrooms"));
        if (bedrooms == null) bedrooms = norm.bedroomsFromTypology(typology);
        if (bedrooms == null) bedrooms = norm.bedroomsFromTypology(get(record, mapping, "title"));

        var propertyType = norm.propertyType(get(record, mapping, "propertyType"));
        if (propertyType == null) propertyType = norm.propertyType(typology);

        var businessType = norm.businessType(get(record, mapping, "businessType"));

        var title = norm.text(get(record, mapping, "title"));
        var description = norm.text(get(record, mapping, "description"));
        if (isBlank(title) && !isBlank(description)) {
            title = description.length() > 80 ? description.substring(0, 80).trim() + "…" : description;
        }

        return new NormalizedListing(
            norm.text(get(record, mapping, "externalRef")),
            businessType, propertyType, title, description,
            norm.decimal(get(record, mapping, "price")),
            bedrooms,
            norm.decimal(get(record, mapping, "bathrooms")),
            norm.integer(get(record, mapping, "garageSpaces")),
            norm.integer(get(record, mapping, "parkingSpaces")),
            norm.decimal(get(record, mapping, "usableAreaM2")),
            norm.decimal(get(record, mapping, "grossAreaM2")),
            norm.decimal(get(record, mapping, "lotAreaM2")),
            norm.text(get(record, mapping, "city")),
            norm.text(get(record, mapping, "district")),
            norm.text(get(record, mapping, "municipality")),
            norm.text(get(record, mapping, "parish")),
            norm.text(get(record, mapping, "neighborhood")),
            norm.text(get(record, mapping, "street")),
            norm.postalCode(get(record, mapping, "postalCode")),
            parseDouble(get(record, mapping, "latitude")),
            parseDouble(get(record, mapping, "longitude")),
            norm.condition(get(record, mapping, "condition")),
            norm.furnished(get(record, mapping, "furnished")),
            norm.energyRating(get(record, mapping, "energyRating"))
        );
    }

    private String get(Map<String, String> record, Map<String, String> mapping, String field) {
        var col = mapping.get(field);
        return col == null ? null : record.get(col);
    }

    // ── Dedup ────────────────────────────────────────────────────────────────

    /** Mapa "chave-de-duplicado" → public_id dos imóveis existentes do anunciante. */
    private Map<String, String> loadExistingTitles(UUID advertiserId) {
        var out = new LinkedHashMap<String, String>();
        var rows = jdbc.sql("""
                SELECT public_id, title_normalized, city, price_amount
                FROM properia.listings
                WHERE advertiser_id = :advId AND status <> 'archived'
                """)
            .param("advId", advertiserId)
            .query((rs, n) -> Map.of(
                "publicId", rs.getString("public_id"),
                "title", rs.getString("title_normalized") == null ? "" : rs.getString("title_normalized"),
                "city", rs.getString("city") == null ? "" : rs.getString("city"),
                "price", rs.getBigDecimal("price_amount") == null ? "" : rs.getBigDecimal("price_amount").toPlainString()
            ))
            .list();
        for (var r : rows) {
            var key = keyOf(r.get("title"), r.get("city"), r.get("price"));
            if (key != null) out.putIfAbsent(key, r.get("publicId"));
        }
        return out;
    }

    private String duplicateKey(NormalizedListing n) {
        return keyOf(n.title(), n.city(), n.price() == null ? "" : n.price().toPlainString());
    }

    private String keyOf(String title, String city, String price) {
        var t = ListingImportNormalizer.key(title == null ? "" : title);
        if (t.isEmpty()) return null;
        return t + "|" + ListingImportNormalizer.key(city == null ? "" : city) + "|" + (price == null ? "" : price);
    }

    // ── Confiança / helpers ──────────────────────────────────────────────────

    private double rowConfidence(NormalizedListing n, List<String> reasons) {
        double score = 1.0;
        score -= reasons.size() * 0.15;
        if (n.bedrooms() == null && !"land".equals(n.propertyType())) score -= 0.05;
        if (isBlank(n.description())) score -= 0.05;
        return Math.max(0.1, Math.min(1.0, score));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim().replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }
}
