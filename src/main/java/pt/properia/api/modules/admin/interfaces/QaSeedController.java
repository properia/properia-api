package pt.properia.api.modules.admin.interfaces;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

/**
 * TEMPORARY — one-time QA seed endpoint.
 * DELETE THIS FILE after running /api/internal/qa-reset once.
 */
@RestController
public class QaSeedController {

    private static final String SECRET = "properia-qa-reset-2026";
    private final JdbcClient jdbc;

    public QaSeedController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @PostMapping("/api/internal/qa-reset")
    public ResponseEntity<?> qaReset(@RequestHeader("X-QA-Secret") String secret) {
        if (!SECRET.equals(secret)) return ResponseEntity.status(403).body(Map.of("error","Forbidden"));
        try {
            runSeed();
            return ResponseEntity.ok(Map.of("ok", true, "counts", Map.of(
                "advertisers", count("advertisers"),
                "users",       count("app_users"),
                "listings",    count("listings"),
                "vision",      count("listing_ai_vision"),
                "summaries",   count("listing_ai_summaries"),
                "zone_scores", count("listing_zone_scores"),
                "tours",       count("listing_commercial")
            )));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private int count(String t) {
        return jdbc.sql("SELECT COUNT(*)::int FROM properia." + t).query(Integer.class).single();
    }

    private void runSeed() {
        // ── LIMPEZA ──────────────────────────────────────────────────────────
        for (String t : new String[]{
            "properia.system_audit_events","properia.operational_metrics_snapshots",
            "properia.crm_audit_events","properia.market_price_benchmarks",
            "properia.partner_leads","properia.leads","properia.visits",
            "properia.chat_messages","properia.chat_participants","properia.chat_conversations",
            "properia.crm_import_items","properia.crm_import_batches",
            "properia.saved_listings","properia.saved_searches","properia.profiles",
            "properia.listings",
            "properia.advertiser_team_invites","properia.advertiser_onboarding",
            "properia.advertiser_users","properia.advertisers",
            "properia.user_consent_events","properia.user_data_requests",
            "properia.user_sessions","properia.user_auth_identities",
            "properia.app_users","properia.job_executions"
        }) { jdbc.sql("TRUNCATE " + t + " CASCADE").update(); }

        // ── IDs ───────────────────────────────────────────────────────────────
        final UUID ADV  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        final UUID U1   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        final UUID U2   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        final UUID U3   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
        final UUID L1   = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
        final UUID L2   = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
        final UUID POI1 = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
        final UUID POI2 = UUID.fromString("dddddddd-0000-0000-0000-000000000002");

        // ── ADVERTISER ────────────────────────────────────────────────────────
        jdbc.sql("""
            INSERT INTO properia.advertisers
              (id,advertiser_type,legal_name,brand_name,slug,email,phone,plan_code,is_active,created_at,updated_at)
            VALUES(:id,'agency','Imobiliária TESTE Lda.','TESTE Imobiliária','teste-imobiliaria',
                   'geral@properia-teste.pt','+351 210 000 000','business',true,now(),now())
        """).param("id", ADV).update();

        // ── USERS ─────────────────────────────────────────────────────────────
        record User(UUID id, String email, String name, String role, String hash) {}
        var users = new User[]{
            new User(U1,"owner@properia-teste.pt","Rafael Teste (Owner)","agency_admin","$2b$12$EJVh01ShszNatnjVIMuP7us6QBnVUwjYA9/YeY/sMH8q0w76IsCr."),
            new User(U2,"agente1@properia-teste.pt","Ana Agente (Editor)","agent","$2b$12$hfQ8RLuu0ajOT9BQgRWx1eyz46U8lwMryPkEIcM42GgIDdPBJk.06"),
            new User(U3,"agente2@properia-teste.pt","Bruno Vendas (Sales)","agent","$2b$12$2o1QlfKWNJWnL001bd3J4.OBy2vFvYUd3a87f60OrFeJ1AgtZXcWu"),
        };
        for (var u : users) {
            jdbc.sql("""
                INSERT INTO properia.app_users(id,email,full_name,role,is_active,created_at,updated_at)
                VALUES(:id,:email,:name,CAST(:role AS properia.user_role),true,now(),now())
            """).param("id",u.id()).param("email",u.email()).param("name",u.name()).param("role",u.role()).update();

            jdbc.sql("""
                INSERT INTO properia.user_auth_identities
                  (id,user_id,provider,provider_user_id,email,email_verified,
                   password_hash,password_algorithm,created_at,updated_at)
                VALUES(gen_random_uuid(),:uid,
                       CAST('local' AS properia.auth_provider),:email,:email,true,
                       :hash,CAST('bcrypt' AS properia.password_algorithm),now(),now())
            """).param("uid",u.id()).param("email",u.email()).param("hash",u.hash()).update();
        }

        // ── MEMBERSHIPS ───────────────────────────────────────────────────────
        record Mb(UUID uid, String role){}
        for (var m : new Mb[]{new Mb(U1,"owner"),new Mb(U2,"editor"),new Mb(U3,"sales")}) {
            jdbc.sql("""
                INSERT INTO properia.advertiser_users(advertiser_id,user_id,membership_role,created_at)
                VALUES(:adv,:uid,CAST(:role AS properia.advertiser_membership_role),now())
            """).param("adv",ADV).param("uid",m.uid()).param("role",m.role()).update();
        }

        // ── ONBOARDING ────────────────────────────────────────────────────────
        jdbc.sql("""
            INSERT INTO properia.advertiser_onboarding
              (advertiser_id,owner_user_id,status,step_current,created_at,updated_at)
            VALUES(:adv,:uid,
                   CAST('active' AS properia.advertiser_onboarding_status),
                   CAST('done'   AS properia.advertiser_onboarding_step),
                   now(),now())
            ON CONFLICT DO NOTHING
        """).param("adv",ADV).param("uid",U1).update();

        // ── LISTING 1 — T2 Lisboa ─────────────────────────────────────────────
        insertListing(L1, ADV,
            "apt-principe-real-t2",
            "Apartamento T2 renovado no Príncipe Real com varanda",
            "apartamento t2 renovado no principe real com varanda",
            2, 1, 1, 88.0, 95.0, 3, 6,
            "T2 renovado com acabamentos premium no Príncipe Real.",
            "Apartamento totalmente renovado em 2023, com cozinha de ilha, roupeiros embutidos e varanda a sul.",
            "sul", true, true, false, false, false, false, true, 480000.0);

        insertLocation(L1,"Rua da Escola Politécnica 58","Lisboa","Lisboa","Misericórdia",38.7175,-9.1489);
        insertFeatures(L1,
            "{\"varanda\":true,\"elevador\":true,\"cozinha_equipada\":true,\"roupeiros_embutidos\":true,\"ar_condicionado\":true,\"vidros_duplos\":true,\"arrecadacao\":true,\"suite\":true}",
            "[\"varanda\",\"elevador\",\"cozinha_equipada\",\"roupeiros_embutidos\",\"ar_condicionado\",\"vidros_duplos\",\"arrecadacao\",\"suite\",\"luz_natural\"]");
        insertMedia(L1, new String[][]{
            {"https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=1200","true"},
            {"https://images.unsplash.com/photo-1600210492493-0946911123ea?w=1200","false"},
            {"https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?w=1200","false"},
            {"https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=1200","false"},
            {"https://images.unsplash.com/photo-1502005229762-cf1b2da7c5d6?w=1200","false"},
        });
        insertTour(L1,"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4");
        insertVision(L1,"contemporaneo","minimalista","remodeled",0.92,8.4,8.8,7.6,8.2,7.9,7.2,6.5,7.4,
            "[\"varanda\",\"luz_natural\",\"cozinha_equipada\",\"roupeiros_embutidos\",\"ar_condicionado\"]");
        insertSummary(L1,
            "T2 renovado com acabamentos premium e varanda a sul no Príncipe Real.",
            "Apartamento totalmente renovado em 2023 com materiais de elevada qualidade.",
            "Ideal para quem valoriza localização central e vida urbana ativa.",
            "O Príncipe Real é uma das zonas mais valorizadas de Lisboa.",
            "Casal jovem ou investimento para arrendamento.",
            "Apartamento T2 Príncipe Real Lisboa — Renovado 2023",
            "T2 renovado no Príncipe Real. 88m², varanda a sul. €480.000.");
        insertPoi(POI1, L1, 3, 2, 2, 4, 1);
        insertZoneScore(L1, POI1, 8.5, 7.2, 9.1, 8.8, 9.3, 7.0);
        insertVisibility(L1, "organic");

        // ── LISTING 2 — T3 Porto ──────────────────────────────────────────────
        insertListing(L2, ADV,
            "apt-foz-douro-t3",
            "Apartamento T3 com vista mar na Foz do Douro — garagem e piscina",
            "apartamento t3 com vista mar na foz do douro garagem e piscina",
            3, 2, 2, 142.0, 158.0, 4, 8,
            "T3 de luxo na Foz do Douro com vista mar, garagem dupla e piscina.",
            "Apartamento de alto padrão na Foz do Douro. Dois quartos en-suite, sala com vista mar.",
            "oeste", true, true, false, false, true, true, true, 895000.0);

        insertLocation(L2,"Avenida do Brasil 210","Porto","Porto","Foz do Douro",41.1521,-8.6768);
        insertFeatures(L2,
            "{\"garagem\":true,\"piscina_condominio\":true,\"varanda\":true,\"elevador\":true,\"cozinha_equipada\":true,\"roupeiros_embutidos\":true,\"ar_condicionado\":true,\"vidros_duplos\":true,\"suite\":true,\"vista_mar\":true}",
            "[\"garagem\",\"piscina_condominio\",\"varanda\",\"elevador\",\"cozinha_equipada\",\"roupeiros_embutidos\",\"ar_condicionado\",\"vidros_duplos\",\"suite\",\"vista_mar\",\"carregamento_eletrico\",\"videoporteiro\"]");
        insertMedia(L2, new String[][]{
            {"https://images.unsplash.com/photo-1613490493576-7fde63acd811?w=1200","true"},
            {"https://images.unsplash.com/photo-1617806118233-18e1de247200?w=1200","false"},
            {"https://images.unsplash.com/photo-1586023492125-27b2c045efd7?w=1200","false"},
            {"https://images.unsplash.com/photo-1630699144867-37acec97df5a?w=1200","false"},
            {"https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=1200","false"},
        });
        insertTour(L2,"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4");
        insertVision(L2,"moderno","mediterranico","remodeled",0.96,9.2,9.4,9.0,9.1,9.5,8.3,7.8,9.6,
            "[\"vista_mar\",\"varanda\",\"luz_natural\",\"cozinha_equipada\",\"roupeiros_embutidos\",\"ar_condicionado\",\"garagem\"]");
        insertSummary(L2,
            "T3 de luxo na Foz com vista mar, garagem dupla e piscina.",
            "Apartamento de alto padrão com acabamentos em mármore. Dois quartos com suíte.",
            "Para quem quer o melhor do Porto: praias a 5 minutos.",
            "A Foz do Douro é o bairro mais premium do Porto.",
            "Famílias exigentes ou executivos expatriados.",
            "Apartamento T3 Foz do Douro Porto — Vista Mar",
            "T3 de luxo na Foz do Douro. 142m², vista mar, garagem, piscina. €895.000.");
        insertPoi(POI2, L2, 2, 3, 3, 5, 2);
        insertZoneScore(L2, POI2, 9.2, 8.8, 7.8, 8.5, 8.8, 9.5);
        insertVisibility(L2, "featured");
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private void insertListing(UUID id, UUID adv, String publicId, String title, String titleNorm,
                                int beds, int baths, int suites, double usableArea, double grossArea,
                                int floor, int totalFloors, String shortDesc, String rawDesc,
                                String sunExp, boolean elevator, boolean balcony, boolean terrace,
                                boolean garden, boolean pool, boolean garage, boolean naturalLight,
                                double price) {
        jdbc.sql("""
            INSERT INTO properia.listings
              (id,public_id,advertiser_id,title,title_normalized,property_type,business_type,status,
               bedrooms,bathrooms,suites,usable_area_m2,gross_area_m2,floor_number,total_floors,
               description_short,description_raw,sun_exposure,
               condition_declared,condition_final,furnished_declared,furnished_final,
               price_amount,price_currency,
               has_elevator,has_balcony,has_terrace,has_garden,has_pool,has_garage,has_natural_light,
               source_type,created_at,updated_at,published_at)
            VALUES(:id,:pub,:adv,:title,:titleNorm,
                   'apartment','sale','published',
                   :beds,:baths,:suites,:usable,:gross,:floor,:floors,
                   :shortDesc,:rawDesc,:sunExp,
                   'remodeled','remodeled','unfurnished','unfurnished',
                   :price,'EUR',
                   :elev,:balc,:terr,:gard,:pool,:gar,:light,
                   'manual',now(),now(),now())
        """)
        .param("id",id).param("pub",publicId).param("adv",adv)
        .param("title",title).param("titleNorm",titleNorm)
        .param("beds",beds).param("baths",(double)baths).param("suites",suites)
        .param("usable",usableArea).param("gross",grossArea)
        .param("floor",floor).param("floors",totalFloors)
        .param("shortDesc",shortDesc).param("rawDesc",rawDesc).param("sunExp",sunExp)
        .param("price",price)
        .param("elev",elevator).param("balc",balcony).param("terr",terrace)
        .param("gard",garden).param("pool",pool).param("gar",garage).param("light",naturalLight)
        .update();
    }

    private void insertLocation(UUID id, String street, String city, String municipality,
                                 String parish, double lat, double lon) {
        jdbc.sql("""
            INSERT INTO properia.listing_location
              (listing_id,street,city,municipality,parish,district,country_code,
               latitude,longitude,location_precision,created_at,updated_at)
            VALUES(:id,:street,:city,:mun,:parish,:mun,'PT',:lat,:lon,
                   CAST('street' AS properia.location_precision),now(),now())
        """).param("id",id).param("street",street).param("city",city)
           .param("mun",municipality).param("parish",parish)
           .param("lat",lat).param("lon",lon).update();
    }

    private void insertFeatures(UUID id, String flagsJson, String tagsJson) {
        jdbc.sql("""
            INSERT INTO properia.listing_features
              (listing_id,feature_flags,feature_tags,created_at,updated_at)
            VALUES(:id,CAST(:flags AS jsonb),CAST(:tags AS jsonb),now(),now())
        """).param("id",id).param("flags",flagsJson).param("tags",tagsJson).update();
    }

    private void insertMedia(UUID lid, String[][] media) {
        for (int i = 0; i < media.length; i++) {
            boolean cover = Boolean.parseBoolean(media[i][1]);
            jdbc.sql("""
                INSERT INTO properia.listing_media
                  (id,listing_id,media_type,source_type,url,sort_order,is_cover,created_at,updated_at)
                VALUES(gen_random_uuid(),:lid,'image','upload',:url,:ord,:cover,now(),now())
            """).param("lid",lid).param("url",media[i][0]).param("ord",i).param("cover",cover).update();
        }
    }

    private void insertTour(UUID id, String url) {
        jdbc.sql("""
            INSERT INTO properia.listing_commercial
              (listing_id,exclusive_listing,online_visit_available,visit_booking_enabled,
               show_phone,show_chat,virtual_tour_status,virtual_tour_url,virtual_tour_generated_at,updated_at)
            VALUES(:id,false,true,true,true,true,'ready',:url,now(),now())
        """).param("id",id).param("url",url).update();
    }

    private void insertVision(UUID id, String style1, String style2, String cond,
                               double condConf, double quality,
                               double light, double spacious, double layout,
                               double premium, double family, double homeOffice, double luxury,
                               String signals) {
        var stylesJson = "[\"" + style1 + "\",\"" + style2 + "\"]";
        jdbc.sql("""
            INSERT INTO properia.listing_ai_vision
              (listing_id,version,provider,model,processed_at,
               styles_detected,style_primary,style_secondary,
               condition_ai,condition_confidence,quality_score,
               furniture_detected,rooms_detected,materials_detected,signals_detected,
               light_quality_score,spaciousness_score,layout_quality_score,
               premium_score,family_friendly_score,home_office_score,luxury_score,
               needs_human_review,raw_response,created_at,updated_at)
            VALUES(:id,1,'openai','gpt-4o',now(),
                   CAST(:styles AS jsonb),:s1,:s2,
                   CAST(:cond AS properia.condition_status),:condConf,:quality,
                   '[]'::jsonb,'[]'::jsonb,'[]'::jsonb,CAST(:signals AS jsonb),
                   :light,:spacious,:layout,:premium,:family,:homeOff,:luxury,
                   false,'{}',now(),now())
        """)
        .param("id",id).param("styles",stylesJson).param("s1",style1).param("s2",style2)
        .param("cond",cond).param("condConf",condConf).param("quality",quality)
        .param("signals",signals)
        .param("light",light).param("spacious",spacious).param("layout",layout)
        .param("premium",premium).param("family",family).param("homeOff",homeOffice).param("luxury",luxury)
        .update();
    }

    private void insertSummary(UUID id, String card, String detail, String lifestyle,
                                String zone, String buyerFit, String seoTitle, String seoDesc) {
        jdbc.sql("""
            INSERT INTO properia.listing_ai_summaries
              (listing_id,summary_card,summary_detail,lifestyle_summary,zone_summary,
               buyer_fit_summary,seo_meta_title,seo_meta_description,
               generated_at,prompt_version,created_at,updated_at)
            VALUES(:id,:card,:detail,:life,:zone,:fit,:seoT,:seoD,now(),2,now(),now())
        """)
        .param("id",id).param("card",card).param("detail",detail)
        .param("life",lifestyle).param("zone",zone).param("fit",buyerFit)
        .param("seoT",seoTitle).param("seoD",seoDesc).update();
    }

    private void insertPoi(UUID poiId, UUID lid,
                            int metro, int supermarket, int park, int restaurant, int school) {
        jdbc.sql("""
            INSERT INTO properia.listing_poi_snapshots
              (id,listing_id,source,radius_m,
               transport_count,supermarket_count,park_count,restaurant_count,schools_count,
               nearest_transport_m,nearest_supermarket_m,nearest_park_m,nearest_school_m,
               processed_at,created_at)
            VALUES(:pid,:lid,'overpass',700,
                   :metro,:supermarket,:park,:restaurant,:school,
                   220,280,350,500,
                   now(),now())
        """)
        .param("pid",poiId).param("lid",lid)
        .param("metro",metro).param("supermarket",supermarket)
        .param("park",park).param("restaurant",restaurant).param("school",school)
        .update();
    }

    private void insertZoneScore(UUID lid, UUID pid,
                                  double lifestyle, double family, double mobility,
                                  double convenience, double walkability, double green) {
        jdbc.sql("""
            INSERT INTO properia.listing_zone_scores
              (listing_id,poi_snapshot_id,
               lifestyle_score,family_score,mobility_score,
               convenience_score,walkability_score,green_score,
               created_at,updated_at)
            VALUES(:lid,:pid,:life,:fam,:mob,:conv,:walk,:green,now(),now())
        """)
        .param("lid",lid).param("pid",pid)
        .param("life",lifestyle).param("fam",family).param("mob",mobility)
        .param("conv",convenience).param("walk",walkability).param("green",green)
        .update();
    }

    private void insertVisibility(UUID id, String status) {
        jdbc.sql("""
            INSERT INTO properia.listing_visibility
              (listing_id,visibility_status,is_featured,created_at,updated_at)
            VALUES(:id,CAST(:status AS properia.visibility_status),:feat,now(),now())
        """).param("id",id).param("status",status).param("feat","featured".equals(status)).update();
    }
}
