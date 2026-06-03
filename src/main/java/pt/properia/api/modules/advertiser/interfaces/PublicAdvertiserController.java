package pt.properia.api.modules.advertiser.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PublicAdvertiserController {

    private final JdbcClient jdbc;

    public PublicAdvertiserController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/public/advertisers/showcase")
    public ResponseEntity<?> showcase() {
        var items = jdbc.sql("""
                SELECT a.id::text, a.brand_name, a.logo_url, a.slug, a.website_url,
                       a.advertiser_type::text
                FROM properia.advertisers a
                WHERE a.is_active = true
                  AND a.advertiser_type != 'private_owner'
                  AND a.brand_name IS NOT NULL
                ORDER BY a.created_at ASC
                LIMIT 30
                """)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("brandName", rs.getString("brand_name"));
                m.put("logoUrl", rs.getString("logo_url"));
                m.put("slug", rs.getString("slug"));
                m.put("websiteUrl", rs.getString("website_url"));
                return (Map<String, Object>) m;
            })
            .list();

        return ResponseEntity.ok(Map.of("data", Map.of("items", items)));
    }

    @GetMapping("/api/public/advertisers/{slug}")
    public ResponseEntity<?> profile(@PathVariable String slug) {
        var result = jdbc.sql("""
                SELECT a.id::text, a.brand_name, a.logo_url, a.slug, a.website_url,
                       a.advertiser_type::text, a.license_number,
                       a.verification_status::text, a.created_at
                FROM properia.advertisers a
                WHERE a.slug = :slug AND a.is_active = true
                """)
            .param("slug", slug)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("brandName", rs.getString("brand_name"));
                m.put("logoUrl", rs.getString("logo_url"));
                m.put("slug", rs.getString("slug"));
                m.put("websiteUrl", rs.getString("website_url"));
                m.put("advertiserType", rs.getString("advertiser_type"));
                m.put("amiLicense", rs.getString("license_number"));
                m.put("verificationStatus", rs.getString("verification_status"));
                m.put("createdAt", rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toInstant().toString() : null);
                m.put("totalListings", 0L);
                return (Map<String, Object>) m;
            })
            .optional();

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // count published listings separately to avoid GROUP BY complexity
        var id = (String) result.get().get("id");
        var count = jdbc.sql("""
                SELECT COUNT(*) FROM properia.listings
                WHERE advertiser_id = :id::uuid AND status::text = 'published'
                """)
            .param("id", id)
            .query(Long.class)
            .single();
        result.get().put("totalListings", count);

        return ResponseEntity.ok(Map.of("data", result.get()));
    }
}
