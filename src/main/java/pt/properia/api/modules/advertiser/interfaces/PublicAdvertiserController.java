package pt.properia.api.modules.advertiser.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
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
}
