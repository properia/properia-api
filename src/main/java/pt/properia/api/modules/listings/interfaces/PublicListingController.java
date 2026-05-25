package pt.properia.api.modules.listings.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.listings.application.GetPublicListingUseCase;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

@RestController
@RequestMapping("/api/listings")
public class PublicListingController {

    private final GetPublicListingUseCase getPublicListingUseCase;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PublicListingController(GetPublicListingUseCase getPublicListingUseCase,
                                   JdbcClient jdbc,
                                   ObjectMapper objectMapper) {
        this.getPublicListingUseCase = getPublicListingUseCase;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<?> getByPublicId(@PathVariable String publicId) {
        var listing = getPublicListingUseCase.execute(new GetPublicListingUseCase.Query(publicId));

        // Enrich with zone snapshot data
        @SuppressWarnings("unchecked")
        var listingMap = (java.util.LinkedHashMap<String, Object>) objectMapper.convertValue(listing, java.util.LinkedHashMap.class);
        var listingId  = listingMap.get("id");
        if (listingId != null) {
            try {
                var zoneData = loadZoneSnapshot(UUID.fromString(listingId.toString()));
                listingMap.put("zoneProcessingStatus", zoneData.get("status"));
                listingMap.put("zoneSummaryV2",        zoneData.get("payload"));
            } catch (Exception e) {
                listingMap.put("zoneProcessingStatus", "not_processed");
                listingMap.put("zoneSummaryV2",        null);
            }
        }

        return ResponseEntity.ok(Map.of("data", listingMap));
    }

    private Map<String, Object> loadZoneSnapshot(UUID listingId) {
        return jdbc.sql("""
            SELECT status, payload
            FROM properia.listing_zone_snapshots
            WHERE listing_id = :lid
            ORDER BY updated_at DESC LIMIT 1
            """)
            .param("lid", listingId)
            .query((rs, n) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("status", rs.getString("status"));
                var payloadStr = rs.getString("payload");
                if (payloadStr != null && !payloadStr.equals("{}")) {
                    try {
                        m.put("payload", objectMapper.readValue(payloadStr, Object.class));
                    } catch (Exception e) {
                        m.put("payload", null);
                    }
                } else {
                    m.put("payload", null);
                }
                return m;
            })
            .optional()
            .orElseGet(() -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("status", "not_processed");
                m.put("payload", null);
                return m;
            });
    }

    // ── Similar listings ───────────────────────────────────────────────────────

    @GetMapping("/{id}/similar")
    public ResponseEntity<?> getSimilar(@PathVariable UUID id) {
        // Look up base listing
        var base = jdbc.sql("""
                SELECT property_type, business_type, district, price_amount
                FROM properia.listings WHERE id = :id AND status = 'published'
                """).param("id", id)
            .query((rs, n) -> Map.of(
                "propertyType", rs.getString("property_type"),
                "businessType", rs.getString("business_type"),
                "district", Optional.ofNullable(rs.getString("district")).orElse(""),
                "priceAmount", rs.getObject("price_amount")
            ))
            .optional();

        if (base.isEmpty()) return ResponseEntity.ok(Map.of("data", Map.of("items", List.of())));

        var b = base.get();
        var items = jdbc.sql("""
                SELECT id, public_id, title, price_amount, price_currency, usable_area_m2,
                       bedrooms, property_type, business_type, city, neighborhood,
                       hero_image_url, published_at
                FROM properia.listings
                WHERE status = 'published'
                  AND business_type::text = :bt
                  AND property_type::text = :pt
                  AND id != :id
                  AND (:district = '' OR district = :district)
                ORDER BY RANDOM() LIMIT 6
                """)
            .param("bt", b.get("businessType"))
            .param("pt", b.get("propertyType"))
            .param("id", id)
            .param("district", b.get("district"))
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("publicId", rs.getString("public_id"));
                m.put("title", rs.getString("title"));
                m.put("priceAmount", rs.getObject("price_amount"));
                m.put("priceCurrency", Optional.ofNullable(rs.getString("price_currency")).orElse("EUR"));
                m.put("usableAreaM2", rs.getObject("usable_area_m2"));
                m.put("bedrooms", rs.getInt("bedrooms"));
                m.put("propertyType", rs.getString("property_type"));
                m.put("businessType", rs.getString("business_type"));
                m.put("city", rs.getString("city"));
                m.put("neighborhood", rs.getString("neighborhood"));
                m.put("heroImageUrl", rs.getString("hero_image_url"));
                m.put("dataPublicacao", rs.getTimestamp("published_at") != null
                    ? rs.getTimestamp("published_at").toInstant().toString()
                    : new Date().toInstant().toString());
                return (Map<String, Object>) m;
            }).list();

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    // ── View tracking ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/view")
    public ResponseEntity<?> trackView(@PathVariable UUID id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @AuthenticationPrincipal JwtClaims claims,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        var sessionKey = body != null && body.get("sessionKey") instanceof String s ? s.trim() : null;
        if (sessionKey == null || sessionKey.isBlank()) {
            sessionKey = extractViewerCookie(request);
        }
        if (sessionKey == null || sessionKey.isBlank()) {
            sessionKey = UUID.randomUUID().toString();
        }
        var userId = claims != null ? claims.userId() : null;

        boolean counted = false;
        long total = 0;
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO properia.listing_detail_views (listing_id, user_id, session_key, viewed_at)
                    VALUES (:lid, :uid, :key, now())
                    ON CONFLICT (listing_id, session_key) DO NOTHING
                    """)
                .param("lid", id).param("uid", userId).param("key", sessionKey)
                .update();
            counted = inserted > 0;
            total = jdbc.sql("SELECT COUNT(*) FROM properia.listing_detail_views WHERE listing_id = :id")
                .param("id", id).query(Long.class).single();
        } catch (Exception ignored) {}

        if (extractViewerCookie(request) == null) {
            response.addHeader("Set-Cookie",
                "listing_viewer_session_id=" + sessionKey + "; Path=/; HttpOnly; SameSite=Lax");
        }

        return ResponseEntity.ok(Map.of("data", Map.of(
            "detailViewsTotal", total,
            "counted", counted
        )));
    }

    // ── Price history ──────────────────────────────────────────────────────────

    @GetMapping("/{id}/price-history")
    public ResponseEntity<?> getPriceHistory(@PathVariable UUID id) {
        var listing = jdbc.sql("SELECT price_amount, status FROM properia.listings WHERE id = :id")
            .param("id", id)
            .query((rs, n) -> Map.of("priceAmount", rs.getObject("price_amount"), "status", rs.getString("status")))
            .optional();
        if (listing.isEmpty() || !"published".equals(listing.get().get("status"))) {
            throw new DomainException("NOT_FOUND", "Imóvel não encontrado.", 404);
        }

        List<Map<String, Object>> items;
        try {
            items = jdbc.sql("""
                    SELECT price_amount, price_currency, recorded_at
                    FROM properia.listing_price_history WHERE listing_id = :id ORDER BY recorded_at ASC
                    """).param("id", id)
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("priceAmount", rs.getDouble("price_amount"));
                    m.put("priceCurrency", Optional.ofNullable(rs.getString("price_currency")).orElse("EUR"));
                    m.put("recordedAt", rs.getTimestamp("recorded_at").toInstant().toString());
                    return (Map<String, Object>) m;
                }).list();
        } catch (Exception ignored) {
            items = List.of();
        }

        var currentPrice = listing.get().get("priceAmount") != null
            ? ((Number) listing.get().get("priceAmount")).doubleValue() : null;
        var prices = items.stream().map(i -> (double) i.get("priceAmount")).toList();
        var lowestPrice = prices.isEmpty() ? null : prices.stream().mapToDouble(d -> d).min().orElse(0);
        var highestPrice = prices.isEmpty() ? null : prices.stream().mapToDouble(d -> d).max().orElse(0);
        var firstPrice = prices.isEmpty() ? null : prices.get(0);
        var dropped = currentPrice != null && firstPrice != null && currentPrice < firstPrice;
        var dropAmount = dropped ? firstPrice - currentPrice : null;
        var dropPercent = dropped && firstPrice != null && firstPrice > 0
            ? (int) Math.round(dropAmount / firstPrice * 100) : null;

        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", items,
            "currentPrice", currentPrice != null ? currentPrice : 0,
            "lowestPrice", lowestPrice != null ? lowestPrice : 0,
            "highestPrice", highestPrice != null ? highestPrice : 0,
            "dropped", dropped,
            "dropAmount", dropAmount != null ? dropAmount : 0,
            "dropPercent", dropPercent != null ? dropPercent : 0
        )));
    }

    // ── Report listing ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/report")
    public ResponseEntity<?> reportListing(@PathVariable UUID id,
                                           @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal JwtClaims claims) {
        if (claims == null) throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        var reason = body.getOrDefault("reason", "other").toString();
        var details = body.containsKey("details") ? body.get("details").toString() : null;
        jdbc.sql("""
                INSERT INTO properia.listing_reports (listing_id, reporter_user_id, reason, details, created_at)
                VALUES (:lid, :uid, :reason, :details, now())
                ON CONFLICT DO NOTHING
                """)
            .param("lid", id).param("uid", claims.userId())
            .param("reason", reason).param("details", details).update();
        return ResponseEntity.status(201).body(Map.of("data", Map.of("ok", true)));
    }

    // ── Featured listings (home page pool) ────────────────────────────────────

    @GetMapping("/featured")
    public ResponseEntity<?> getFeaturedListings() {
        var items = jdbc.sql("""
                SELECT l.id, l.public_id, l.title, l.business_type, l.property_type,
                       l.price_amount, l.price_currency, l.bedrooms, l.bathrooms,
                       l.usable_area_m2, l.hero_image_url, l.is_featured, l.status,
                       l.published_at, l.updated_at, l.city, l.district, l.neighborhood,
                       l.parish, l.postal_code, l.latitude, l.longitude,
                       l.has_garage, l.has_balcony, l.has_terrace, l.has_garden,
                       l.has_natural_light, l.has_elevator, l.has_pool,
                       l.floor_number, l.total_floors, l.suites, l.energy_rating,
                       l.condition_final, l.furnished_final, l.description_short,
                       l.sun_exposure,
                       loc.location_precision, loc.street,
                       zs.zone_label_primary, zs.zone_summary_short,
                       a.brand_name as advertiser_name
                FROM properia.listings l
                LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id
                LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id
                INNER JOIN properia.advertisers a ON a.id = l.advertiser_id
                WHERE l.status = 'published' AND l.is_featured = true AND a.is_active = true
                ORDER BY l.published_at ASC
                LIMIT 60
                """)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("publicId", rs.getString("public_id"));
                m.put("title", rs.getString("title"));
                m.put("businessType", rs.getString("business_type"));
                m.put("propertyType", rs.getString("property_type"));
                m.put("priceAmount", rs.getObject("price_amount"));
                m.put("priceCurrency", Optional.ofNullable(rs.getString("price_currency")).orElse("EUR"));
                m.put("bedrooms", rs.getInt("bedrooms"));
                m.put("bathrooms", rs.getObject("bathrooms"));
                m.put("suites", rs.getObject("suites"));
                m.put("usableAreaM2", rs.getObject("usable_area_m2"));
                m.put("heroImageUrl", rs.getString("hero_image_url"));
                m.put("isFeatured", rs.getBoolean("is_featured"));
                m.put("status", rs.getString("status"));
                m.put("visibilityStatus", "featured");
                m.put("publishedAt", rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant().toString() : null);
                m.put("updatedAt", rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant().toString() : null);
                m.put("city", rs.getString("city"));
                m.put("district", rs.getString("district"));
                m.put("neighborhood", rs.getString("neighborhood"));
                m.put("parish", rs.getString("parish"));
                m.put("postalCode", rs.getString("postal_code"));
                m.put("locationPrecision", rs.getString("location_precision"));
                m.put("street", rs.getString("street"));
                m.put("latitude", rs.getObject("latitude"));
                m.put("longitude", rs.getObject("longitude"));
                m.put("hasGarage", rs.getBoolean("has_garage"));
                m.put("hasBalcony", rs.getBoolean("has_balcony"));
                m.put("hasTerrace", rs.getBoolean("has_terrace"));
                m.put("hasGarden", rs.getBoolean("has_garden"));
                m.put("hasNaturalLight", rs.getBoolean("has_natural_light"));
                m.put("hasElevator", rs.getBoolean("has_elevator"));
                m.put("hasPool", rs.getBoolean("has_pool"));
                m.put("floorNumber", rs.getObject("floor_number"));
                m.put("totalFloors", rs.getObject("total_floors"));
                m.put("energyRating", rs.getString("energy_rating"));
                m.put("conditionStatus", rs.getString("condition_final"));
                m.put("furnishedStatus", rs.getString("furnished_final"));
                m.put("descriptionShort", rs.getString("description_short"));
                m.put("sunExposure", rs.getString("sun_exposure"));
                m.put("zoneLabelPrimary", rs.getString("zone_label_primary"));
                m.put("zoneSummaryShort", rs.getString("zone_summary_short"));
                m.put("advertiserTrust", Map.of("advertiserName", Optional.ofNullable(rs.getString("advertiser_name")).orElse("")));
                m.put("featureTags", List.of());
                m.put("tipologia", null);
                m.put("imageUrls", List.of());
                return (Map<String, Object>) m;
            }).list();
        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    // ── Sitemap entries ────────────────────────────────────────────────────────

    @GetMapping("/sitemap-entries")
    public ResponseEntity<?> getSitemapEntries() {
        var entries = jdbc.sql("""
                SELECT public_id, updated_at FROM properia.listings
                WHERE status = 'published' ORDER BY updated_at DESC
                """)
            .query((rs, n) -> Map.of(
                "publicId", rs.getString("public_id"),
                "updatedAt", rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant().toString() : null
            ))
            .list();
        return ResponseEntity.ok(Map.of("data", Map.of("items", entries)));
    }

    // ── By search params ───────────────────────────────────────────────────────

    @GetMapping("/by-search")
    public ResponseEntity<?> bySearch(
            @RequestParam String location,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String propertyType,
            @RequestParam(required = false) Double priceMin,
            @RequestParam(required = false) Double priceMax,
            @RequestParam(defaultValue = "6") int limit) {
        if (location == null || location.trim().length() < 2) {
            throw new DomainException("BAD_REQUEST", "location param required (min 2 chars).", 400);
        }
        var cap = Math.min(limit, 12);
        var loc = "%" + location.trim().toLowerCase() + "%";

        var items = jdbc.sql("""
                SELECT id, public_id, title, price_amount, price_currency, usable_area_m2,
                       bedrooms, property_type, business_type, city, neighborhood,
                       hero_image_url, published_at
                FROM properia.listings
                WHERE status = 'published'
                  AND (LOWER(city) LIKE :loc OR LOWER(neighborhood) LIKE :loc
                    OR LOWER(parish) LIKE :loc OR LOWER(district) LIKE :loc)
                  AND (:bt IS NULL OR business_type::text = :bt)
                  AND (:pt IS NULL OR property_type::text = :pt)
                  AND (:priceMin IS NULL OR price_amount::numeric >= :priceMin)
                  AND (:priceMax IS NULL OR price_amount::numeric <= :priceMax)
                ORDER BY published_at DESC LIMIT :lim
                """)
            .param("loc", loc)
            .param("bt", businessType)
            .param("pt", propertyType)
            .param("priceMin", priceMin)
            .param("priceMax", priceMax)
            .param("lim", cap)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("publicId", rs.getString("public_id"));
                m.put("title", rs.getString("title"));
                m.put("priceAmount", rs.getObject("price_amount"));
                m.put("priceCurrency", Optional.ofNullable(rs.getString("price_currency")).orElse("EUR"));
                m.put("usableAreaM2", rs.getObject("usable_area_m2"));
                m.put("bedrooms", rs.getInt("bedrooms"));
                m.put("propertyType", rs.getString("property_type"));
                m.put("businessType", rs.getString("business_type"));
                m.put("city", rs.getString("city"));
                m.put("neighborhood", rs.getString("neighborhood"));
                m.put("heroImageUrl", rs.getString("hero_image_url"));
                return (Map<String, Object>) m;
            }).list();

        return ResponseEntity.ok(items);
    }

    // ── Media ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/media")
    public ResponseEntity<?> getMedia(@PathVariable UUID id) {
        var media = jdbc.sql("""
                SELECT id, listing_id, url, thumbnail_url, media_type, display_order,
                       file_name, file_size, width, height, is_hero, created_at
                FROM properia.listing_media WHERE listing_id = :id ORDER BY display_order ASC
                """).param("id", id)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("listingId", rs.getString("listing_id"));
                m.put("url", rs.getString("url"));
                m.put("thumbnailUrl", rs.getString("thumbnail_url"));
                m.put("mediaType", rs.getString("media_type"));
                m.put("displayOrder", rs.getInt("display_order"));
                m.put("fileName", rs.getString("file_name"));
                m.put("fileSize", rs.getObject("file_size"));
                m.put("width", rs.getObject("width"));
                m.put("height", rs.getObject("height"));
                m.put("isHero", rs.getBoolean("is_hero"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return (Map<String, Object>) m;
            }).list();
        return ResponseEntity.ok(Map.of("data", media));
    }

    private String extractViewerCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(c -> "listing_viewer_session_id".equals(c.getName()))
            .map(jakarta.servlet.http.Cookie::getValue)
            .findFirst().orElse(null);
    }
}
