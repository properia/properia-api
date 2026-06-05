package pt.properia.api.modules.admin.interfaces;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * TEMPORARY — one-time QA seed endpoint.
 * DELETE THIS FILE after running /api/internal/qa-reset once.
 */
@RestController
public class QaSeedController {

    private static final String SECRET = "properia-qa-reset-2026";
    private final JdbcClient jdbc;

    public QaSeedController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/internal/qa-reset")
    public ResponseEntity<?> qaReset(@RequestHeader("X-QA-Secret") String secret) {
        if (!SECRET.equals(secret)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        try {
            runSeed();
            var counts = Map.of(
                "advertisers", count("advertisers"),
                "users",       count("app_users"),
                "listings",    count("listings"),
                "vision",      count("listing_ai_vision"),
                "summaries",   count("listing_ai_summaries"),
                "zone_scores", count("listing_zone_scores"),
                "tours",       count("listing_commercial")
            );
            return ResponseEntity.ok(Map.of("ok", true, "counts", counts));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*)::int FROM properia." + table)
            .query(Integer.class).single();
    }

    private void runSeed() {
        // ── LIMPEZA ─────────────────────────────────────────────────────────
        String[] truncates = {
            "properia.system_audit_events",
            "properia.operational_metrics_snapshots",
            "properia.crm_audit_events",
            "properia.market_price_benchmarks",
            "properia.partner_leads",
            "properia.leads",
            "properia.visits",
            "properia.chat_messages",
            "properia.chat_participants",
            "properia.chat_conversations",
            "properia.crm_import_items",
            "properia.crm_import_batches",
            "properia.saved_listings",
            "properia.saved_searches",
            "properia.profiles",
            "properia.listings",
            "properia.advertiser_team_invites",
            "properia.advertiser_onboarding",
            "properia.advertiser_users",
            "properia.advertisers",
            "properia.user_consent_events",
            "properia.user_data_requests",
            "properia.user_sessions",
            "properia.user_auth_identities",
            "properia.app_users",
            "properia.job_executions",
        };
        for (String t : truncates) {
            jdbc.sql("TRUNCATE " + t + " CASCADE").update();
        }

        // ── IDs FIXOS ────────────────────────────────────────────────────────
        final String ADV  = "aaaaaaaa-0000-0000-0000-000000000001";
        final String U1   = "bbbbbbbb-0000-0000-0000-000000000001";
        final String U2   = "bbbbbbbb-0000-0000-0000-000000000002";
        final String U3   = "bbbbbbbb-0000-0000-0000-000000000003";
        final String L1   = "cccccccc-0000-0000-0000-000000000001";
        final String L2   = "cccccccc-0000-0000-0000-000000000002";
        final String POI1 = "dddddddd-0000-0000-0000-000000000001";
        final String POI2 = "dddddddd-0000-0000-0000-000000000002";

        // ── ADVERTISER ────────────────────────────────────────────────────────
        jdbc.sql("""
            INSERT INTO properia.advertisers
              (id, advertiser_type, legal_name, brand_name, slug,
               email, phone, plan_code, is_active, created_at, updated_at)
            VALUES
              (:id, 'agency', 'Imobiliária TESTE Lda.', 'TESTE Imobiliária', 'teste-imobiliaria',
               'geral@properia-teste.pt', '+351 210 000 000', 'business', true, now(), now())
        """).param("id", java.util.UUID.fromString(ADV)).update();

        // ── USERS ─────────────────────────────────────────────────────────────
        Object[][] users = {
            {U1, "owner@properia-teste.pt",  "Rafael Teste (Owner)",  "agency_admin",
             "$2b$12$EJVh01ShszNatnjVIMuP7us6QBnVUwjYA9/YeY/sMH8q0w76IsCr."},
            {U2, "agente1@properia-teste.pt", "Ana Agente (Editor)",   "agent",
             "$2b$12$hfQ8RLuu0ajOT9BQgRWx1eyz46U8lwMryPkEIcM42GgIDdPBJk.06"},
            {U3, "agente2@properia-teste.pt", "Bruno Vendas (Sales)",  "agent",
             "$2b$12$2o1QlfKWNJWnL001bd3J4.OBy2vFvYUd3a87f60OrFeJ1AgtZXcWu"},
        };
        for (var u : users) {
            var uid = java.util.UUID.fromString((String) u[0]);
            jdbc.sql("""
                INSERT INTO properia.app_users
                  (id, email, display_name, role, email_verified, created_at, updated_at)
                VALUES (:id, :email, :name, :role::properia.user_role, true, now(), now())
            """).param("id", uid).param("email", u[1]).param("name", u[2]).param("role", u[3]).update();

            jdbc.sql("""
                INSERT INTO properia.user_auth_identities
                  (id, user_id, provider, provider_user_id, password_hash, created_at, updated_at)
                VALUES (gen_random_uuid(), :uid, 'local', :email, :hash, now(), now())
            """).param("uid", uid).param("email", u[1]).param("hash", u[4]).update();
        }

        // ── MEMBERSHIPS ───────────────────────────────────────────────────────
        Object[][] members = {{U1,"owner"},{U2,"editor"},{U3,"sales"}};
        for (var m : members) {
            jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role, created_at)
                VALUES (:adv, :uid, :role, now())
            """).param("adv", java.util.UUID.fromString(ADV))
               .param("uid", java.util.UUID.fromString((String) m[0]))
               .param("role", m[1]).update();
        }

        // Onboarding done
        jdbc.sql("""
            INSERT INTO properia.advertiser_onboarding (advertiser_id, step, completed, created_at, updated_at)
            VALUES (:adv, 'done', true, now(), now()) ON CONFLICT DO NOTHING
        """).param("adv", java.util.UUID.fromString(ADV)).update();

        // ── LISTING 1 — T2 Lisboa ─────────────────────────────────────────────
        var l1 = java.util.UUID.fromString(L1);
        jdbc.sql("""
            INSERT INTO properia.listings
              (id, advertiser_id, title, property_type, business_type, status,
               bedrooms, bathrooms, suites, area, floor, total_floors,
               description_short, description_long, feature_tags,
               sun_exposure, condition_status, furnished_status,
               source_type, created_at, updated_at, published_at)
            VALUES (
              :id, :adv, 'Apartamento T2 renovado no Príncipe Real com varanda',
              'apartment'::properia.property_type, 'sale'::properia.business_type,
              'published'::properia.listing_status,
              2, 1, 1, 88, 3, 6,
              'T2 renovado com acabamentos premium no Príncipe Real.',
              'Apartamento totalmente renovado em 2023, com cozinha de ilha, roupeiros embutidos e varanda virada a sul. Edifício com elevador e arrecadação.',
              ARRAY['varanda','elevador','cozinha_equipada','roupeiros_embutidos','ar_condicionado','vidros_duplos','arrecadacao','suite','luz_natural'],
              'sul', 'remodeled'::properia.condition_status,
              'unfurnished'::properia.furnished_status,
              'manual'::properia.listing_source_type, now(), now(), now()
            )
        """).param("id", l1).param("adv", java.util.UUID.fromString(ADV)).update();

        jdbc.sql("""
            INSERT INTO properia.listing_location
              (listing_id, address, parish, municipality, district, country, latitude, longitude, location_precision, created_at, updated_at)
            VALUES (:id, 'Rua da Escola Politécnica 58, 3º Dto', 'Misericórdia', 'Lisboa', 'Lisboa', 'PT',
                    38.7175, -9.1489, 'street'::properia.location_precision, now(), now())
        """).param("id", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_pricing (listing_id, price, price_period, price_per_sqm, created_at, updated_at)
            VALUES (:id, 480000, 'sale'::properia.price_period, 5455, now(), now())
        """).param("id", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_dimensions (listing_id, gross_area, net_area, created_at, updated_at)
            VALUES (:id, 95, 88, now(), now())
        """).param("id", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_features
              (listing_id, has_garage, has_private_parking, has_balcony, has_terrace, has_garden, has_pool, has_elevator, has_natural_light, created_at, updated_at)
            VALUES (:id, false, false, true, false, false, false, true, true, now(), now())
        """).param("id", l1).update();

        String[][] media1 = {
            {"https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=1200","true"},
            {"https://images.unsplash.com/photo-1600210492493-0946911123ea?w=1200","false"},
            {"https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?w=1200","false"},
            {"https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=1200","false"},
            {"https://images.unsplash.com/photo-1502005229762-cf1b2da7c5d6?w=1200","false"},
        };
        for (int i = 0; i < media1.length; i++) {
            jdbc.sql("""
                INSERT INTO properia.listing_media
                  (id, listing_id, media_type, url, sort_order, is_hero, source_type, created_at, updated_at)
                VALUES (gen_random_uuid(), :lid, 'image'::properia.media_type, :url, :ord, :hero, 'upload'::properia.media_source_type, now(), now())
            """).param("lid", l1).param("url", media1[i][0]).param("ord", i).param("hero", Boolean.parseBoolean(media1[i][1])).update();
        }

        jdbc.sql("""
            INSERT INTO properia.listing_commercial
              (listing_id, exclusive_listing, online_visit_available, visit_booking_enabled,
               show_phone, show_chat, virtual_tour_status, virtual_tour_url, virtual_tour_generated_at, updated_at)
            VALUES (:id, false, true, true, true, true,
                    'ready', 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
                    now(), now())
        """).param("id", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_ai_vision
              (listing_id, version, provider, model, processed_at,
               styles_detected, style_primary, style_secondary,
               condition_ai, condition_confidence, quality_score,
               furniture_detected, rooms_detected, materials_detected, signals_detected,
               light_quality_score, spaciousness_score, layout_quality_score,
               premium_score, family_friendly_score, home_office_score, luxury_score,
               needs_human_review, raw_response, created_at, updated_at)
            VALUES (:id, 1, 'openai', 'gpt-4o', now(),
                    '["contemporaneo","minimalista"]', 'contemporaneo', 'minimalista',
                    'remodeled'::properia.condition_status, 0.92, 8.4,
                    '["sofa","mesa_jantar","cama","roupeiro_embutido","cozinha_montada"]',
                    '["sala","quarto","cozinha","casa_de_banho"]',
                    '["madeira_clara","pedra","vidro"]',
                    '["varanda","luz_natural","cozinha_equipada","roupeiros_embutidos","ar_condicionado"]',
                    8.8, 7.6, 8.2, 7.9, 7.2, 6.5, 7.4, false, '{}', now(), now())
        """).param("id", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_ai_summaries
              (listing_id, summary_card, summary_detail, lifestyle_summary, zone_summary,
               buyer_fit_summary, seo_meta_title, seo_meta_description,
               generated_at, prompt_version, created_at, updated_at)
            VALUES (:id,
                    'T2 renovado com acabamentos premium e varanda a sul no Príncipe Real.',
                    'Apartamento totalmente renovado em 2023 com materiais de elevada qualidade. Cozinha de ilha com electrodomésticos integrados, roupeiros embutidos em todos os quartos.',
                    'Ideal para quem valoriza localização central, qualidade de acabamentos e vida urbana ativa. A 3 minutos do Jardim do Príncipe Real.',
                    'O Príncipe Real é uma das zonas mais valorizadas de Lisboa. Rica em vida cultural e excelente rede de transportes.',
                    'Excelente opção para casal jovem ou investimento para arrendamento.',
                    'Apartamento T2 Príncipe Real Lisboa — Renovado 2023',
                    'T2 renovado no Príncipe Real, Lisboa. 88m², varanda a sul, cozinha equipada. €480.000.',
                    now(), 2, now(), now())
        """).param("id", l1).update();

        var poi1 = java.util.UUID.fromString(POI1);
        jdbc.sql("""
            INSERT INTO properia.listing_poi_snapshots
              (id, listing_id, provider, radius_m, poi_count, poi_data, processed_at, created_at)
            VALUES (:pid, :lid, 'overpass', 500, 8,
                    '[{"type":"metro","name":"Rato","distance_m":180},{"type":"park","name":"Jardim Príncipe Real","distance_m":280},{"type":"supermarket","name":"Pingo Doce","distance_m":220}]',
                    now(), now())
        """).param("pid", poi1).param("lid", l1).update();

        jdbc.sql("""
            INSERT INTO properia.listing_zone_scores
              (listing_id, poi_snapshot_id, zone_score, transport_score, commerce_score,
               education_score, health_score, leisure_score, safety_score, walkability_score,
               computed_at, created_at, updated_at)
            VALUES (:id, :pid, 88, 91, 85, 74, 79, 90, 82, 93, now(), now(), now())
        """).param("id", l1).param("pid", poi1).update();

        jdbc.sql("INSERT INTO properia.listing_visibility (listing_id, status, created_at, updated_at) VALUES (:id, 'organic'::properia.visibility_status, now(), now())")
            .param("id", l1).update();

        // ── LISTING 2 — T3 Porto ──────────────────────────────────────────────
        var l2 = java.util.UUID.fromString(L2);
        jdbc.sql("""
            INSERT INTO properia.listings
              (id, advertiser_id, title, property_type, business_type, status,
               bedrooms, bathrooms, suites, area, floor, total_floors,
               description_short, description_long, feature_tags,
               sun_exposure, condition_status, furnished_status,
               source_type, created_at, updated_at, published_at)
            VALUES (
              :id, :adv, 'Apartamento T3 com vista mar na Foz do Douro — garagem e piscina',
              'apartment'::properia.property_type, 'sale'::properia.business_type,
              'published'::properia.listing_status,
              3, 2, 2, 142, 4, 8,
              'T3 de luxo na Foz do Douro com vista mar, garagem dupla e piscina.',
              'Apartamento de alto padrão na Foz do Douro. Dois quartos en-suite, sala com vista mar, cozinha equipada com ilha. Edifício com piscina e ginásio.',
              ARRAY['garagem','piscina_condominio','varanda','elevador','cozinha_equipada','roupeiros_embutidos','ar_condicionado','vidros_duplos','suite','vista_mar','carregamento_eletrico','videoporteiro'],
              'oeste', 'remodeled'::properia.condition_status,
              'furnished'::properia.furnished_status,
              'manual'::properia.listing_source_type, now(), now(), now()
            )
        """).param("id", l2).param("adv", java.util.UUID.fromString(ADV)).update();

        jdbc.sql("""
            INSERT INTO properia.listing_location
              (listing_id, address, parish, municipality, district, country, latitude, longitude, location_precision, created_at, updated_at)
            VALUES (:id, 'Avenida do Brasil 210, 4º Esq', 'Foz do Douro', 'Porto', 'Porto', 'PT',
                    41.1521, -8.6768, 'street'::properia.location_precision, now(), now())
        """).param("id", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_pricing (listing_id, price, price_period, price_per_sqm, created_at, updated_at)
            VALUES (:id, 895000, 'sale'::properia.price_period, 6303, now(), now())
        """).param("id", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_dimensions (listing_id, gross_area, net_area, created_at, updated_at)
            VALUES (:id, 158, 142, now(), now())
        """).param("id", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_features
              (listing_id, has_garage, has_private_parking, has_balcony, has_terrace, has_garden, has_pool, has_elevator, has_natural_light, created_at, updated_at)
            VALUES (:id, true, true, true, false, false, true, true, true, now(), now())
        """).param("id", l2).update();

        String[][] media2 = {
            {"https://images.unsplash.com/photo-1613490493576-7fde63acd811?w=1200","true"},
            {"https://images.unsplash.com/photo-1617806118233-18e1de247200?w=1200","false"},
            {"https://images.unsplash.com/photo-1586023492125-27b2c045efd7?w=1200","false"},
            {"https://images.unsplash.com/photo-1630699144867-37acec97df5a?w=1200","false"},
            {"https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=1200","false"},
        };
        for (int i = 0; i < media2.length; i++) {
            jdbc.sql("""
                INSERT INTO properia.listing_media
                  (id, listing_id, media_type, url, sort_order, is_hero, source_type, created_at, updated_at)
                VALUES (gen_random_uuid(), :lid, 'image'::properia.media_type, :url, :ord, :hero, 'upload'::properia.media_source_type, now(), now())
            """).param("lid", l2).param("url", media2[i][0]).param("ord", i).param("hero", Boolean.parseBoolean(media2[i][1])).update();
        }

        jdbc.sql("""
            INSERT INTO properia.listing_commercial
              (listing_id, exclusive_listing, online_visit_available, visit_booking_enabled,
               show_phone, show_chat, virtual_tour_status, virtual_tour_url, virtual_tour_generated_at, updated_at)
            VALUES (:id, false, true, true, true, true,
                    'ready', 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4',
                    now(), now())
        """).param("id", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_ai_vision
              (listing_id, version, provider, model, processed_at,
               styles_detected, style_primary, style_secondary,
               condition_ai, condition_confidence, quality_score,
               furniture_detected, rooms_detected, materials_detected, signals_detected,
               light_quality_score, spaciousness_score, layout_quality_score,
               premium_score, family_friendly_score, home_office_score, luxury_score,
               needs_human_review, raw_response, created_at, updated_at)
            VALUES (:id, 1, 'openai', 'gpt-4o', now(),
                    '["moderno","mediterranico"]', 'moderno', 'mediterranico',
                    'remodeled'::properia.condition_status, 0.96, 9.2,
                    '["sofa","mesa_jantar","cama","roupeiro_embutido","cozinha_montada","eletrodomesticos_integrados"]',
                    '["sala","quarto_master","quarto_2","cozinha","casa_de_banho"]',
                    '["marmore","madeira_escura","vidro","aco_inox"]',
                    '["vista_mar","varanda","luz_natural","cozinha_equipada","roupeiros_embutidos","ar_condicionado","garagem"]',
                    9.4, 9.0, 9.1, 9.5, 8.3, 7.8, 9.6, false, '{}', now(), now())
        """).param("id", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_ai_summaries
              (listing_id, summary_card, summary_detail, lifestyle_summary, zone_summary,
               buyer_fit_summary, seo_meta_title, seo_meta_description,
               generated_at, prompt_version, created_at, updated_at)
            VALUES (:id,
                    'T3 de luxo na Foz com vista mar, garagem dupla e piscina.',
                    'Apartamento de alto padrão com acabamentos em mármore e madeira. Dois quartos com suíte, sala com vista mar direta e varanda de 18m².',
                    'Para quem quer o melhor do Porto: praias a 5 minutos, Parque da Cidade a 800m.',
                    'A Foz do Douro é o bairro mais premium do Porto, frente ao Atlântico.',
                    'Ideal para famílias exigentes ou executivos expatriados. Elevado potencial de valorização.',
                    'Apartamento T3 Foz do Douro Porto — Vista Mar Luxo',
                    'T3 de luxo na Foz do Douro, Porto. 142m², vista mar, garagem dupla, piscina. €895.000.',
                    now(), 2, now(), now())
        """).param("id", l2).update();

        var poi2 = java.util.UUID.fromString(POI2);
        jdbc.sql("""
            INSERT INTO properia.listing_poi_snapshots
              (id, listing_id, provider, radius_m, poi_count, poi_data, processed_at, created_at)
            VALUES (:pid, :lid, 'overpass', 500, 7,
                    '[{"type":"beach","name":"Praia dos Ingleses","distance_m":180},{"type":"park","name":"Parque da Cidade","distance_m":820},{"type":"supermarket","name":"Continente Foz","distance_m":350}]',
                    now(), now())
        """).param("pid", poi2).param("lid", l2).update();

        jdbc.sql("""
            INSERT INTO properia.listing_zone_scores
              (listing_id, poi_snapshot_id, zone_score, transport_score, commerce_score,
               education_score, health_score, leisure_score, safety_score, walkability_score,
               computed_at, created_at, updated_at)
            VALUES (:id, :pid, 92, 78, 82, 86, 80, 96, 91, 88, now(), now(), now())
        """).param("id", l2).param("pid", poi2).update();

        jdbc.sql("INSERT INTO properia.listing_visibility (listing_id, status, created_at, updated_at) VALUES (:id, 'featured'::properia.visibility_status, now(), now())")
            .param("id", l2).update();
    }
}
