package pt.properia.api.modules.search.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.search.application.SearchListingsUseCase;
import pt.properia.api.modules.search.application.dto.SearchParams;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchListingsUseCase useCase;
    private final JdbcClient jdbc;

    public SearchController(SearchListingsUseCase useCase, JdbcClient jdbc) {
        this.useCase = useCase;
        this.jdbc = jdbc;
    }

    // TEMPORARY diagnostic endpoint 5 — EXPLAIN ANALYZE + column group tests
    @GetMapping("/listings/diag5")
    public ResponseEntity<?> diag5() {
        var r = new java.util.LinkedHashMap<String, Object>();

        var ids = jdbc.sql("SELECT l.id::text FROM properia.listings l WHERE l.status = 'published' ORDER BY l.published_at DESC NULLS LAST LIMIT 6 OFFSET 0")
            .query(String.class).list();
        if (ids.isEmpty()) { r.put("no_results", true); return ResponseEntity.ok(r); }
        var lit = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));

        var joins = " FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id"
            + " LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url, ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true";

        // 1: only lateral outputs (no simple columns)
        time(r, "t1_only_lateral_outputs",
            "SELECT l.id::text, lm.image_urls_arr, lm.cover_url, dv_agg.view_count, ph_agg.change_count, ph_agg.first_price, ph_agg.last_change_at"
            + joins + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC");

        // 2: only simple string/int columns from l (no laterals in SELECT)
        time(r, "t2_only_simple_l_columns",
            "SELECT l.id::text, l.title, l.city, l.district, l.status, l.business_type, l.property_type, l.bedrooms, l.price_amount, l.is_featured, l.published_at, l.updated_at"
            + joins + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC");

        // 3: full 50-column SELECT with WHERE 1=0 (no rows → tests planning time only)
        time(r, "t3_fullselect_where_false",
            "SELECT l.id, l.public_id, l.advertiser_id, l.title, l.business_type, l.property_type, l.status, l.visibility_status, l.is_featured, l.price_amount, l.price_currency, p.condo_fee, p.property_tax_annual, p.municipal_tax_estimate, p.deposit_required, l.bedrooms, l.bathrooms, l.suites, l.garage_spaces, l.parking_spaces, l.usable_area_m2, l.gross_area_m2, l.lot_area_m2, l.city, l.district, l.parish, l.neighborhood, loc.street, l.postal_code, loc.location_precision, COALESCE(l.latitude, loc.latitude) AS latitude, COALESCE(l.longitude, loc.longitude) AS longitude, COALESCE(l.hero_image_url, lm.cover_url) AS hero_image_url, lm.image_urls_arr, l.description_short, l.energy_rating, l.condition_final, l.furnished_final, l.has_garage, l.has_private_parking, l.has_balcony, l.has_terrace, l.has_garden, l.has_pool, l.has_elevator, l.has_natural_light, l.has_equipped_kitchen, l.has_built_in_closets, l.has_double_glazing, l.has_solar_panels, l.has_barbecue, l.has_laundry_area, l.pool_type, lf.feature_tags, l.floor_number, l.total_floors, l.construction_year, l.renovation_year, l.sun_exposure, l.is_immediately_available, l.available_from, l.published_at, l.updated_at, com.floorplan_url, com.youtube_tour_url, com.virtual_tour_url, com.virtual_tour_status, zs.zone_label_primary, zs.zone_summary_short, COALESCE(dv_agg.view_count, 0) AS detail_views_total, COALESCE(ph_agg.change_count, 0) AS ph_change_count, ph_agg.first_price AS ph_first_price, ph_agg.last_change_at AS ph_last_change_at"
            + joins + " WHERE 1=0");

        // 4: EXPLAIN (no analyze, fast) on full Phase2 query
        try {
            var explainSql = "EXPLAIN SELECT l.id, l.public_id, l.advertiser_id, l.title, l.business_type, l.property_type, l.status, l.visibility_status, l.is_featured, l.price_amount, l.price_currency, p.condo_fee, p.property_tax_annual, p.municipal_tax_estimate, p.deposit_required, l.bedrooms, l.bathrooms, l.suites, l.garage_spaces, l.parking_spaces, l.usable_area_m2, l.gross_area_m2, l.lot_area_m2, l.city, l.district, l.parish, l.neighborhood, loc.street, l.postal_code, loc.location_precision, COALESCE(l.latitude, loc.latitude) AS latitude, COALESCE(l.longitude, loc.longitude) AS longitude, COALESCE(l.hero_image_url, lm.cover_url) AS hero_image_url, lm.image_urls_arr, l.description_short, l.energy_rating, l.condition_final, l.furnished_final, l.has_garage, l.has_private_parking, l.has_balcony, l.has_terrace, l.has_garden, l.has_pool, l.has_elevator, l.has_natural_light, l.has_equipped_kitchen, l.has_built_in_closets, l.has_double_glazing, l.has_solar_panels, l.has_barbecue, l.has_laundry_area, l.pool_type, lf.feature_tags, l.floor_number, l.total_floors, l.construction_year, l.renovation_year, l.sun_exposure, l.is_immediately_available, l.available_from, l.published_at, l.updated_at, com.floorplan_url, com.youtube_tour_url, com.virtual_tour_url, com.virtual_tour_status, zs.zone_label_primary, zs.zone_summary_short, COALESCE(dv_agg.view_count, 0) AS detail_views_total, COALESCE(ph_agg.change_count, 0) AS ph_change_count, ph_agg.first_price AS ph_first_price, ph_agg.last_change_at AS ph_last_change_at"
                + joins + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC";
            var plan = jdbc.sql(explainSql).query((rs, n) -> rs.getString(1)).list();
            r.put("explain_plan", plan);
        } catch (Exception e) {
            r.put("explain_error", e.getMessage());
        }

        return ResponseEntity.ok(r);
    }

    // TEMPORARY diagnostic endpoint 4 — isolates which JOIN/clause makes Phase2 slow
    @GetMapping("/listings/diag4")
    public ResponseEntity<?> diag4() {
        var r = new java.util.LinkedHashMap<String, Object>();

        var ids = jdbc.sql("SELECT l.id::text FROM properia.listings l WHERE l.status = 'published' ORDER BY l.published_at DESC NULLS LAST LIMIT 6 OFFSET 0")
            .query(String.class).list();
        if (ids.isEmpty()) { r.put("no_results", true); return ResponseEntity.ok(r); }
        var lit = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));

        // Base: same as diag2 phase2f but full SELECT (no listing_features, no listing_commercial, no ORDER BY)
        time(r, "t1_base_nofeatures_nocommercial_noorder",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ")");

        // + ORDER BY
        time(r, "t2_add_orderby",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC");

        // + listing_features (sem ORDER BY)
        time(r, "t3_add_features_noorder",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ")");

        // + listing_commercial (sem ORDER BY)
        time(r, "t4_add_commercial_noorder",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ")");

        // + both features+commercial (sem ORDER BY)
        time(r, "t5_features_commercial_noorder",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id"
            + " LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ")");

        // full (features + commercial + ORDER BY) — should be 12s if this is the problem
        time(r, "t6_full_features_commercial_order",
            "SELECT l.id::text FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id"
            + " LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC");

        return ResponseEntity.ok(r);
    }

    private void time(java.util.Map<String, Object> r, String label, String sql) {
        long t = System.currentTimeMillis();
        try {
            jdbc.sql(sql).query((rs, n) -> rs.getString(1)).list();
        } catch (Exception e) {
            r.put(label + "_error", e.getMessage());
        }
        r.put(label + "_ms", System.currentTimeMillis() - t);
    }

    // TEMPORARY diagnostic endpoint 3 — times each rs.getXxx() group inside full mapRow
    @GetMapping("/listings/diag3")
    public ResponseEntity<?> diag3() throws java.sql.SQLException {
        var r = new java.util.LinkedHashMap<String, Object>();
        long t;

        t = System.currentTimeMillis();
        var ids = jdbc.sql("SELECT l.id::text FROM properia.listings l WHERE l.status = 'published' ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC LIMIT 6 OFFSET 0")
            .query(String.class).list();
        r.put("p1_ids_ms", System.currentTimeMillis() - t);

        if (ids.isEmpty()) { r.put("no_results", true); return ResponseEntity.ok(r); }

        var lit = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));

        var detailSql = "SELECT l.id, l.public_id, l.advertiser_id, l.title, l.business_type, l.property_type, l.status, l.visibility_status, l.is_featured, l.price_amount, l.price_currency, p.condo_fee, p.property_tax_annual, p.municipal_tax_estimate, p.deposit_required, l.bedrooms, l.bathrooms, l.suites, l.garage_spaces, l.parking_spaces, l.usable_area_m2, l.gross_area_m2, l.lot_area_m2, l.city, l.district, l.parish, l.neighborhood, loc.street, l.postal_code, loc.location_precision, COALESCE(l.latitude, loc.latitude) AS latitude, COALESCE(l.longitude, loc.longitude) AS longitude, COALESCE(l.hero_image_url, lm.cover_url) AS hero_image_url, lm.image_urls_arr, l.description_short, l.energy_rating, l.condition_final, l.furnished_final, l.has_garage, l.has_private_parking, l.has_balcony, l.has_terrace, l.has_garden, l.has_pool, l.has_elevator, l.has_natural_light, l.has_equipped_kitchen, l.has_built_in_closets, l.has_double_glazing, l.has_solar_panels, l.has_barbecue, l.has_laundry_area, l.pool_type, lf.feature_tags, l.floor_number, l.total_floors, l.construction_year, l.renovation_year, l.sun_exposure, l.is_immediately_available, l.available_from, l.published_at, l.updated_at, com.floorplan_url, com.youtube_tour_url, com.virtual_tour_url, com.virtual_tour_status, zs.zone_label_primary, zs.zone_summary_short, COALESCE(dv_agg.view_count, 0) AS detail_views_total, COALESCE(ph_agg.change_count, 0) AS ph_change_count, ph_agg.first_price AS ph_first_price, ph_agg.last_change_at AS ph_last_change_at"
            + " FROM properia.listings l"
            + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
            + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
            + " LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id"
            + " LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id"
            + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
            + " LEFT JOIN LATERAL (SELECT (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url, ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
            + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
            + " WHERE l.id::text IN (" + lit + ") ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC";

        var grp = new java.util.LinkedHashMap<String, Long>();

        t = System.currentTimeMillis();
        jdbc.sql(detailSql).query((rs, rowNum) -> {
            long tA = System.currentTimeMillis();
            rs.getString("id"); rs.getString("public_id"); rs.getString("advertiser_id");
            rs.getString("title"); rs.getString("business_type"); rs.getString("property_type");
            rs.getString("status"); rs.getString("visibility_status");
            grp.merge("A_getstring_ids", System.currentTimeMillis() - tA, Long::sum);

            long tB = System.currentTimeMillis();
            rs.getBoolean("is_featured");
            rs.getBigDecimal("price_amount"); rs.getString("price_currency");
            rs.getBigDecimal("condo_fee"); rs.getBigDecimal("property_tax_annual");
            rs.getBigDecimal("municipal_tax_estimate"); rs.getBigDecimal("deposit_required");
            grp.merge("B_bool_bigdec", System.currentTimeMillis() - tB, Long::sum);

            long tC = System.currentTimeMillis();
            rs.getInt("bedrooms"); rs.getBigDecimal("bathrooms"); rs.getInt("suites");
            rs.getObject("garage_spaces"); rs.getObject("parking_spaces");
            rs.getBigDecimal("usable_area_m2"); rs.getBigDecimal("gross_area_m2"); rs.getBigDecimal("lot_area_m2");
            grp.merge("C_ints_areas", System.currentTimeMillis() - tC, Long::sum);

            long tD = System.currentTimeMillis();
            rs.getString("city"); rs.getString("district"); rs.getString("parish");
            rs.getString("neighborhood"); rs.getString("street"); rs.getString("postal_code");
            rs.getString("location_precision");
            rs.getObject("latitude"); rs.getObject("longitude");
            grp.merge("D_location_geo", System.currentTimeMillis() - tD, Long::sum);

            long tE = System.currentTimeMillis();
            rs.getString("hero_image_url"); rs.getString("image_urls_arr");
            rs.getString("description_short"); rs.getString("energy_rating");
            rs.getString("condition_final"); rs.getString("furnished_final");
            grp.merge("E_image_desc", System.currentTimeMillis() - tE, Long::sum);

            long tF = System.currentTimeMillis();
            rs.getBoolean("has_garage"); rs.getBoolean("has_private_parking");
            rs.getBoolean("has_balcony"); rs.getBoolean("has_terrace"); rs.getBoolean("has_garden");
            rs.getBoolean("has_pool"); rs.getBoolean("has_elevator"); rs.getBoolean("has_natural_light");
            rs.getBoolean("has_equipped_kitchen"); rs.getBoolean("has_built_in_closets");
            rs.getBoolean("has_double_glazing"); rs.getBoolean("has_solar_panels");
            rs.getBoolean("has_barbecue"); rs.getBoolean("has_laundry_area");
            grp.merge("F_has_booleans", System.currentTimeMillis() - tF, Long::sum);

            long tG = System.currentTimeMillis();
            rs.getString("pool_type"); rs.getString("feature_tags");
            rs.getObject("floor_number"); rs.getObject("total_floors");
            rs.getObject("construction_year"); rs.getObject("renovation_year");
            rs.getString("sun_exposure"); rs.getBoolean("is_immediately_available");
            rs.getString("available_from");
            grp.merge("G_misc", System.currentTimeMillis() - tG, Long::sum);

            long tH = System.currentTimeMillis();
            rs.getTimestamp("published_at");
            rs.getTimestamp("updated_at");
            grp.merge("H_timestamps", System.currentTimeMillis() - tH, Long::sum);

            long tI = System.currentTimeMillis();
            rs.getString("floorplan_url"); rs.getString("youtube_tour_url");
            rs.getString("virtual_tour_url"); rs.getString("virtual_tour_status");
            rs.getString("zone_label_primary"); rs.getString("zone_summary_short");
            rs.getInt("detail_views_total");
            grp.merge("I_commercial_zone", System.currentTimeMillis() - tI, Long::sum);

            long tJ = System.currentTimeMillis();
            int chg = rs.getInt("ph_change_count");
            if (chg > 0) { rs.getObject("ph_first_price"); rs.getBigDecimal("price_amount"); rs.getTimestamp("ph_last_change_at"); }
            grp.merge("J_price_history", System.currentTimeMillis() - tJ, Long::sum);

            return rowNum;
        }).list();
        r.put("p2_total_ms", System.currentTimeMillis() - t);
        r.put("p2_breakdown", grp);

        t = System.currentTimeMillis();
        jdbc.sql("SELECT COUNT(*) FROM properia.listings l WHERE l.status = 'published'").query(Long.class).single();
        r.put("p3_count_ms", System.currentTimeMillis() - t);

        return ResponseEntity.ok(r);
    }

    // TEMPORARY diagnostic endpoint 2 — simulates full search() step by step
    @GetMapping("/listings/diag2")
    public ResponseEntity<?> diag2() {
        var r = new java.util.LinkedHashMap<String, Object>();
        long t;

        // Phase 1: same as search Phase 1
        t = System.currentTimeMillis();
        var ids = jdbc.sql("SELECT l.id::text AS id FROM properia.listings l WHERE l.status = 'published' ORDER BY l.published_at DESC NULLS LAST, l.created_at DESC LIMIT :lim OFFSET :off")
            .param("lim", 6).param("off", 0).query(String.class).list();
        r.put("phase1_ms", System.currentTimeMillis() - t);
        r.put("ids", ids);

        if (!ids.isEmpty()) {
            var lit = ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));

            // Phase 2a: just listings JOIN (no laterals)
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id LEFT JOIN properia.listing_features lf ON lf.listing_id = l.id LEFT JOIN properia.listing_commercial com ON com.listing_id = l.id LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2a_joins_only_ms", System.currentTimeMillis() - t);

            // Phase 2b: add listing_media lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS c FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image') lm ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2b_media_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2c: add detail_views lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2c_detail_views_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2d: add price_history lateral
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS change_count FROM properia.listing_price_history WHERE listing_id = l.id) ph ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2d_price_history_lateral_ms", System.currentTimeMillis() - t);

            // Phase 2e: all 3 laterals combined
            t = System.currentTimeMillis();
            jdbc.sql("SELECT l.id::text FROM properia.listings l LEFT JOIN LATERAL (SELECT COUNT(*) AS c FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image') lm ON true LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv ON true LEFT JOIN LATERAL (SELECT COUNT(*) AS change_count FROM properia.listing_price_history WHERE listing_id = l.id) ph ON true WHERE l.id::text IN (" + lit + ")")
                .query(String.class).list();
            r.put("phase2e_all_laterals_ms", System.currentTimeMillis() - t);

            // Phase 2f: FULL query with ARRAY_AGG (old approach — rs.getArray path)
            t = System.currentTimeMillis();
            var fullSqlArrayAgg = "SELECT l.id::text, lm.image_urls_arr, COALESCE(dv_agg.view_count,0) AS dv, COALESCE(ph_agg.change_count,0) AS ph"
                + " FROM properia.listings l"
                + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
                + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
                + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
                + " LEFT JOIN LATERAL (SELECT (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url, ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC) AS image_urls_arr FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
                + " WHERE l.id::text IN (" + lit + ")";
            jdbc.sql(fullSqlArrayAgg).query((rs, n) -> rs.getArray("image_urls_arr")).list();
            r.put("phase2f_array_agg_rs_getarray_ms", System.currentTimeMillis() - t);

            // Phase 2g: FULL query with ARRAY_TO_STRING (new approach — rs.getString path)
            t = System.currentTimeMillis();
            var fullSqlStr = "SELECT l.id::text, lm.image_urls_str, COALESCE(dv_agg.view_count,0) AS dv, COALESCE(ph_agg.change_count,0) AS ph"
                + " FROM properia.listings l"
                + " LEFT JOIN properia.listing_pricing p ON p.listing_id = l.id"
                + " LEFT JOIN properia.listing_location loc ON loc.listing_id = l.id"
                + " LEFT JOIN properia.listing_zone_scores zs ON zs.listing_id = l.id"
                + " LEFT JOIN LATERAL (SELECT (ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC))[1] AS cover_url, ARRAY_TO_STRING(ARRAY_AGG(url ORDER BY is_cover DESC, sort_order ASC), '|') AS image_urls_str FROM (SELECT url, is_cover, sort_order FROM properia.listing_media WHERE listing_id = l.id AND media_type::text = 'image' ORDER BY is_cover DESC, sort_order ASC LIMIT 5) top_media) lm ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*) AS view_count FROM properia.listing_detail_views WHERE listing_id = l.id) dv_agg ON true"
                + " LEFT JOIN LATERAL (SELECT COUNT(*)::int AS change_count, (ARRAY_AGG(price_amount ORDER BY recorded_at ASC))[1] AS first_price, MAX(recorded_at) AS last_change_at FROM properia.listing_price_history WHERE listing_id = l.id) ph_agg ON true"
                + " WHERE l.id::text IN (" + lit + ")";
            jdbc.sql(fullSqlStr).query((rs, n) -> rs.getString("image_urls_str")).list();
            r.put("phase2g_array_to_string_rs_getstring_ms", System.currentTimeMillis() - t);
        }

        // count() equivalent
        t = System.currentTimeMillis();
        long cnt = jdbc.sql("SELECT COUNT(*) FROM properia.listings l WHERE l.status = 'published'").query(Long.class).single();
        r.put("count_ms", System.currentTimeMillis() - t);
        r.put("count_val", cnt);

        return ResponseEntity.ok(r);
    }

    // TEMPORARY diagnostic endpoint — remove after root cause is identified
    @GetMapping("/listings/diag")
    public ResponseEntity<?> diag() {
        var results = new java.util.LinkedHashMap<String, Object>();

        // Step 1: bare connection ping
        long t = System.currentTimeMillis();
        jdbc.sql("SELECT 1").query(Integer.class).single();
        results.put("ping_ms", System.currentTimeMillis() - t);

        // Step 2: count published (no ORDER BY, no LIMIT)
        t = System.currentTimeMillis();
        long cnt = jdbc.sql("SELECT COUNT(*) FROM properia.listings WHERE status = 'published'")
            .query(Long.class).single();
        results.put("count_published_ms", System.currentTimeMillis() - t);
        results.put("count_published", cnt);

        // Step 3: select id (no ORDER BY, no LIMIT)
        t = System.currentTimeMillis();
        var ids1 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published'")
            .query(String.class).list();
        results.put("select_ids_no_order_ms", System.currentTimeMillis() - t);
        results.put("ids_count", ids1.size());

        // Step 4: select id with ORDER BY (no LIMIT)
        t = System.currentTimeMillis();
        var ids2 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST")
            .query(String.class).list();
        results.put("select_ids_with_order_ms", System.currentTimeMillis() - t);

        // Step 5: select id with ORDER BY + LIMIT (literal)
        t = System.currentTimeMillis();
        var ids3 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST LIMIT 24 OFFSET 0")
            .query(String.class).list();
        results.put("select_ids_order_limit_literal_ms", System.currentTimeMillis() - t);

        // Step 6: select id with ORDER BY + LIMIT (named param)
        t = System.currentTimeMillis();
        var ids4 = jdbc.sql("SELECT id::text FROM properia.listings WHERE status = 'published' ORDER BY published_at DESC NULLS LAST LIMIT :lim OFFSET :off")
            .param("lim", 24).param("off", 0)
            .query(String.class).list();
        results.put("select_ids_order_limit_param_ms", System.currentTimeMillis() - t);

        // Step 7: count listing_detail_views (check table size)
        t = System.currentTimeMillis();
        long dvCount = jdbc.sql("SELECT COUNT(*) FROM properia.listing_detail_views")
            .query(Long.class).single();
        results.put("listing_detail_views_total_ms", System.currentTimeMillis() - t);
        results.put("listing_detail_views_count", dvCount);

        // Step 8: count listing_media (check table size)
        t = System.currentTimeMillis();
        long mediaCount = jdbc.sql("SELECT COUNT(*) FROM properia.listing_media")
            .query(Long.class).single();
        results.put("listing_media_total_ms", System.currentTimeMillis() - t);
        results.put("listing_media_count", mediaCount);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/listings")
    public ResponseEntity<?> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "todos") String negocio,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String quartos,
            @RequestParam(required = false) Double precoMin,
            @RequestParam(required = false) Double precoMax,
            @RequestParam(required = false) String certificado,
            @RequestParam(required = false) String mobilia,
            @RequestParam(required = false) Double bathroomMin,
            @RequestParam(required = false) Double areaMin,
            @RequestParam(required = false) Double areaMax,
            @RequestParam(required = false) String conditionStatus,
            @RequestParam(required = false) Integer floorMin,
            @RequestParam(required = false) String sunExposure,
            @RequestParam(required = false) String features,
            @RequestParam(required = false) String disponibilidade,
            @RequestParam(defaultValue = "recente") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize,
            @RequestParam(required = false) Double commuteLat,
            @RequestParam(required = false) Double commuteLng,
            @RequestParam(required = false) String commuteMode,
            @RequestParam(required = false) Integer commuteMaxMinutes,
            @RequestParam(defaultValue = "false") boolean roomHasPrivateBathroom,
            @RequestParam(defaultValue = "false") boolean roomBillsIncluded,
            @RequestParam(defaultValue = "false") boolean roomInternetIncluded,
            @RequestParam(defaultValue = "false") boolean roomCoupleAllowed,
            @RequestParam(defaultValue = "false") boolean roomIsExterior,
            @RequestParam(required = false) Integer roomMinStayMonths,
            @RequestParam(defaultValue = "false") boolean commercialHasShopfront,
            @RequestParam(required = false) String commercialStreetVisibility,
            @RequestParam(defaultValue = "false") boolean commercialHasVehicleAccess,
            @RequestParam(defaultValue = "false") boolean commercialHasFluePipe,
            @RequestParam(defaultValue = "false") boolean commercialHasExtractionSystem,
            @RequestParam(required = false) String commercialPermittedUse,
            @RequestParam(required = false) String advertiserId) {

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(48, Math.max(1, pageSize));

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features),
            disponibilidade != null ? disponibilidade : "",
            sort, safePage, safePageSize,
            commuteLat, commuteLng, commuteMode, commuteMaxMinutes,
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId
        );

        var result = useCase.search(params);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping("/listings/count")
    public ResponseEntity<?> count(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "todos") String negocio,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String quartos,
            @RequestParam(required = false) Double precoMin,
            @RequestParam(required = false) Double precoMax,
            @RequestParam(required = false) String certificado,
            @RequestParam(required = false) String mobilia,
            @RequestParam(required = false) Double bathroomMin,
            @RequestParam(required = false) Double areaMin,
            @RequestParam(required = false) Double areaMax,
            @RequestParam(required = false) String conditionStatus,
            @RequestParam(required = false) Integer floorMin,
            @RequestParam(required = false) String sunExposure,
            @RequestParam(required = false) String features,
            @RequestParam(required = false) String disponibilidade,
            @RequestParam(defaultValue = "false") boolean roomHasPrivateBathroom,
            @RequestParam(defaultValue = "false") boolean roomBillsIncluded,
            @RequestParam(defaultValue = "false") boolean roomInternetIncluded,
            @RequestParam(defaultValue = "false") boolean roomCoupleAllowed,
            @RequestParam(defaultValue = "false") boolean roomIsExterior,
            @RequestParam(required = false) Integer roomMinStayMonths,
            @RequestParam(defaultValue = "false") boolean commercialHasShopfront,
            @RequestParam(required = false) String commercialStreetVisibility,
            @RequestParam(defaultValue = "false") boolean commercialHasVehicleAccess,
            @RequestParam(defaultValue = "false") boolean commercialHasFluePipe,
            @RequestParam(defaultValue = "false") boolean commercialHasExtractionSystem,
            @RequestParam(required = false) String commercialPermittedUse,
            @RequestParam(required = false) String advertiserId) {

        var params = new SearchParams(
            q, negocio,
            splitCsv(tipo), splitInts(quartos),
            precoMin, precoMax,
            splitCsv(certificado), splitCsv(mobilia),
            bathroomMin, areaMin, areaMax,
            splitCsv(conditionStatus), floorMin,
            splitCsv(sunExposure), splitCsv(features),
            disponibilidade != null ? disponibilidade : "",
            "recente", 1, 1,
            null, null, null, null,
            roomHasPrivateBathroom, roomBillsIncluded, roomInternetIncluded,
            roomCoupleAllowed, roomIsExterior, roomMinStayMonths,
            commercialHasShopfront, splitCsv(commercialStreetVisibility),
            commercialHasVehicleAccess, commercialHasFluePipe,
            commercialHasExtractionSystem, splitCsv(commercialPermittedUse),
            advertiserId
        );

        long total = useCase.count(params);
        return ResponseEntity.ok(Map.of("data", Map.of("total", total)));
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private List<Integer> splitInts(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .flatMap(s -> {
                try {
                    return java.util.stream.Stream.of(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    return java.util.stream.Stream.empty();
                }
            })
            .toList();
    }
}
