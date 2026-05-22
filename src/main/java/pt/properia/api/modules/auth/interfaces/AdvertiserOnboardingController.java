package pt.properia.api.modules.auth.interfaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.*;

@RestController
@RequestMapping("/api/advertisers/onboarding")
public class AdvertiserOnboardingController {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AdvertiserOnboardingController(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getOnboarding(@AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var data = loadOnboarding(claims.userId());
        if (data == null) {
            var nullData = new java.util.LinkedHashMap<String, Object>();
            nullData.put("data", null);
            return ResponseEntity.ok(nullData);
        }
        return ResponseEntity.ok(Map.of("data", data));
    }

    @PatchMapping("/me")
    public ResponseEntity<?> updateOnboarding(@RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);
        var adv = loadOnboarding(claims.userId());
        if (adv == null) throw new DomainException("NOT_FOUND", "Onboarding não iniciado.", 404);

        var advertiserId = UUID.fromString((String) adv.get("advertiserId"));

        // ── Update advertisers table ──────────────────────────────────────────
        var advSets = new ArrayList<String>();
        var advParams = new LinkedHashMap<String, Object>();
        advParams.put("id", advertiserId);

        if (body.containsKey("brandName"))   { advSets.add("brand_name = :brandName");   advParams.put("brandName", body.get("brandName")); }
        if (body.containsKey("legalName"))   { advSets.add("legal_name = :legalName");   advParams.put("legalName", body.get("legalName")); }
        if (body.containsKey("phone"))       { advSets.add("phone = :phone");             advParams.put("phone", body.get("phone")); }
        if (body.containsKey("email"))       { advSets.add("email = :email");             advParams.put("email", body.get("email")); }
        if (body.containsKey("websiteUrl"))  { advSets.add("website_url = :websiteUrl");  advParams.put("websiteUrl", body.get("websiteUrl")); }
        if (body.containsKey("logoUrl"))     { advSets.add("logo_url = :logoUrl");        advParams.put("logoUrl", body.get("logoUrl")); }
        if (body.containsKey("licenseNumber")) { advSets.add("license_number = :licenseNumber"); advParams.put("licenseNumber", body.get("licenseNumber")); }
        if (body.containsKey("taxNumber"))   { advSets.add("tax_number = :taxNumber");   advParams.put("taxNumber", body.get("taxNumber")); }
        if (body.containsKey("professionalRegistrationType") && body.get("professionalRegistrationType") != null) {
            advSets.add("professional_registration_type = :profRegType::properia.professional_registration_type");
            advParams.put("profRegType", body.get("professionalRegistrationType"));
        }
        if (body.containsKey("businessAddressLine1")) { advSets.add("business_address_line_1 = :bAddr"); advParams.put("bAddr", body.get("businessAddressLine1")); }
        if (body.containsKey("businessPostalCode"))   { advSets.add("business_postal_code = :bPostal"); advParams.put("bPostal", body.get("businessPostalCode")); }
        if (body.containsKey("businessCity"))         { advSets.add("business_city = :bCity");         advParams.put("bCity", body.get("businessCity")); }
        if (body.containsKey("businessDistrict"))     { advSets.add("business_district = :bDistrict"); advParams.put("bDistrict", body.get("businessDistrict")); }
        if (body.containsKey("businessCountry"))      { advSets.add("business_country = :bCountry");   advParams.put("bCountry", body.get("businessCountry")); }

        // Extra fields stored in settings jsonb
        var extraSettings = new LinkedHashMap<String, Object>();
        if (body.containsKey("listingAuthorityType"))        extraSettings.put("listingAuthorityType", body.get("listingAuthorityType"));
        if (body.containsKey("ownershipDeclarationAccepted")) extraSettings.put("ownershipDeclarationAccepted", body.get("ownershipDeclarationAccepted"));
        if (body.containsKey("licensedEntityName"))          extraSettings.put("licensedEntityName", body.get("licensedEntityName"));
        if (body.containsKey("licensedEntityTaxNumber"))     extraSettings.put("licensedEntityTaxNumber", body.get("licensedEntityTaxNumber"));
        if (body.containsKey("licensedEntityRelationship"))  extraSettings.put("licensedEntityRelationship", body.get("licensedEntityRelationship"));
        if (!extraSettings.isEmpty()) {
            try {
                advSets.add("settings = settings || :extraSettings::jsonb");
                advParams.put("extraSettings", objectMapper.writeValueAsString(extraSettings));
            } catch (Exception ignored) {}
        }

        if (!advSets.isEmpty()) {
            advSets.add("updated_at = now()");
            var sql = "UPDATE properia.advertisers SET " + String.join(", ", advSets) + " WHERE id = :id";
            var q = jdbc.sql(sql);
            for (var e : advParams.entrySet()) q = q.param(e.getKey(), e.getValue());
            q.update();
        }

        // ── Update advertiser_onboarding table ────────────────────────────────
        var aoSets = new ArrayList<String>();
        var aoParams = new LinkedHashMap<String, Object>();
        aoParams.put("id", advertiserId);

        if (body.containsKey("stepCurrent") && body.get("stepCurrent") != null) {
            aoSets.add("step_current = :stepCurrent::properia.advertiser_onboarding_step");
            aoParams.put("stepCurrent", body.get("stepCurrent"));
        }
        if (body.containsKey("serviceDistricts")) {
            try {
                aoSets.add("service_districts = :serviceDistricts::jsonb");
                aoParams.put("serviceDistricts", objectMapper.writeValueAsString(body.get("serviceDistricts")));
            } catch (Exception ignored) {}
        }
        if (body.containsKey("propertySpecialties")) {
            try {
                aoSets.add("property_specialties = :propertySpecialties::jsonb");
                aoParams.put("propertySpecialties", objectMapper.writeValueAsString(body.get("propertySpecialties")));
            } catch (Exception ignored) {}
        }
        if (body.containsKey("acceptsOnlineVisits")) {
            aoSets.add("accepts_online_visits = :acceptsOnlineVisits");
            aoParams.put("acceptsOnlineVisits", body.get("acceptsOnlineVisits"));
        }

        // markStepCompleted: append to completed_steps array if not already present
        if (body.containsKey("markStepCompleted") && body.get("markStepCompleted") != null) {
            var step = body.get("markStepCompleted").toString();
            aoSets.add("""
                completed_steps = CASE
                  WHEN completed_steps @> jsonb_build_array(:markStep::text) THEN completed_steps
                  ELSE completed_steps || jsonb_build_array(:markStep::text)
                END
                """);
            aoParams.put("markStep", step);
        }

        if (!aoSets.isEmpty()) {
            aoSets.add("updated_at = now()");
            var sql = "UPDATE properia.advertiser_onboarding SET " + String.join(", ", aoSets) + " WHERE advertiser_id = :id";
            var q = jdbc.sql(sql);
            for (var e : aoParams.entrySet()) q = q.param(e.getKey(), e.getValue());
            q.update();
        }

        var result = loadOnboarding(claims.userId());
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PostMapping("/start")
    public ResponseEntity<?> startOnboarding(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal JwtClaims claims) {
        requireAuth(claims);

        var existing = jdbc.sql("""
                SELECT a.id FROM properia.advertisers a
                JOIN properia.advertiser_users au ON au.advertiser_id = a.id
                WHERE au.user_id = :uid AND au.membership_role = 'owner'
                LIMIT 1
                """).param("uid", claims.userId())
            .query((rs, n) -> rs.getString("id")).optional();
        if (existing.isPresent()) {
            throw new DomainException("CONFLICT", "Já tens um anunciante associado.", 409);
        }

        var advertiserType = body.getOrDefault("advertiserType", "private_owner").toString();
        var brandName = body.containsKey("brandName") ? body.get("brandName").toString() : claims.name();
        var id = UUID.randomUUID();
        var slug = generateSlug(brandName, id);

        jdbc.sql("""
                INSERT INTO properia.advertisers
                  (id, brand_name, legal_name, slug, advertiser_type, verification_status, is_active, created_at, updated_at)
                VALUES (:id, :name, :name, :slug, :type::properia.advertiser_type, 'verified_basic', true, now(), now())
                """)
            .param("id", id).param("name", brandName).param("slug", slug)
            .param("type", advertiserType).update();

        jdbc.sql("""
                INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role, created_at)
                VALUES (:adv, :uid, 'owner', now())
                """)
            .param("adv", id).param("uid", claims.userId()).update();

        // Create onboarding progress row
        jdbc.sql("""
                INSERT INTO properia.advertiser_onboarding
                  (advertiser_id, owner_user_id, status, step_current, completed_steps,
                   advertiser_type_selected, service_districts, property_specialties,
                   accepts_online_visits, created_at, updated_at)
                VALUES (:adv, :uid, 'active', 'basic_profile', '[]'::jsonb,
                        :type::properia.advertiser_type, '[]'::jsonb, '[]'::jsonb,
                        false, now(), now())
                ON CONFLICT (advertiser_id) DO NOTHING
                """)
            .param("adv", id).param("uid", claims.userId())
            .param("type", advertiserType).update();

        var result = loadOnboarding(claims.userId());
        return ResponseEntity.status(201).body(Map.of("data", result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadOnboarding(UUID userId) {
        return jdbc.sql("""
                SELECT a.id, a.brand_name, a.legal_name, a.slug, a.advertiser_type,
                       a.verification_status, a.is_active, a.license_number, a.phone,
                       a.email, a.website_url, a.logo_url, a.tax_number,
                       a.professional_registration_type,
                       a.business_address_line_1, a.business_postal_code,
                       a.business_city, a.business_district, a.business_country,
                       a.tax_number_verified_at, a.professional_registration_verified_at,
                       a.settings, a.created_at,
                       au.user_id as owner_user_id,
                       COALESCE(ao.status::text, 'active') as onboarding_status,
                       COALESCE(ao.step_current::text, 'done') as step_current,
                       COALESCE(ao.completed_steps, '[]'::jsonb) as completed_steps,
                       COALESCE(ao.advertiser_type_selected::text, a.advertiser_type::text) as advertiser_type_selected,
                       COALESCE(ao.service_districts, '[]'::jsonb) as service_districts,
                       COALESCE(ao.property_specialties, '[]'::jsonb) as property_specialties,
                       COALESCE(ao.accepts_online_visits, false) as accepts_online_visits,
                       ao.verification_notes, ao.submitted_at, ao.reviewed_at
                FROM properia.advertisers a
                JOIN properia.advertiser_users au ON au.advertiser_id = a.id
                LEFT JOIN properia.advertiser_onboarding ao ON ao.advertiser_id = a.id
                WHERE au.user_id = :uid AND au.membership_role = 'owner'
                ORDER BY a.created_at DESC LIMIT 1
                """).param("uid", userId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("advertiserId", rs.getString("id"));
                m.put("ownerUserId", rs.getString("owner_user_id"));
                m.put("brandName", rs.getString("brand_name"));
                m.put("legalName", rs.getString("legal_name"));
                m.put("slug", rs.getString("slug"));
                m.put("advertiserTypeSelected", rs.getString("advertiser_type_selected"));
                m.put("verificationStatus", rs.getString("verification_status"));
                m.put("isActive", rs.getBoolean("is_active"));
                m.put("licenseNumber", rs.getString("license_number"));
                m.put("taxNumber", rs.getString("tax_number"));
                m.put("professionalRegistrationType", rs.getString("professional_registration_type"));
                m.put("phone", rs.getString("phone"));
                m.put("email", rs.getString("email"));
                m.put("websiteUrl", rs.getString("website_url"));
                m.put("logoUrl", rs.getString("logo_url"));
                m.put("businessAddressLine1", rs.getString("business_address_line_1"));
                m.put("businessPostalCode", rs.getString("business_postal_code"));
                m.put("businessCity", rs.getString("business_city"));
                m.put("businessDistrict", rs.getString("business_district"));
                m.put("businessCountry", rs.getString("business_country"));
                m.put("taxNumberVerifiedAt", rs.getTimestamp("tax_number_verified_at") != null
                    ? rs.getTimestamp("tax_number_verified_at").toInstant().toString() : null);
                m.put("professionalRegistrationVerifiedAt", rs.getTimestamp("professional_registration_verified_at") != null
                    ? rs.getTimestamp("professional_registration_verified_at").toInstant().toString() : null);
                m.put("verificationSubmittedAt", rs.getTimestamp("submitted_at") != null
                    ? rs.getTimestamp("submitted_at").toInstant().toString() : null);
                m.put("verificationReviewedAt", rs.getTimestamp("reviewed_at") != null
                    ? rs.getTimestamp("reviewed_at").toInstant().toString() : null);
                m.put("verificationNotes", rs.getString("verification_notes"));
                m.put("suspensionReason", null);
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("status", rs.getString("onboarding_status"));
                m.put("stepCurrent", rs.getString("step_current"));
                // Parse jsonb arrays
                m.put("completedSteps", parseJsonArray(rs.getString("completed_steps")));
                m.put("serviceDistricts", parseJsonArray(rs.getString("service_districts")));
                m.put("propertySpecialties", parseJsonArray(rs.getString("property_specialties")));
                m.put("acceptsOnlineVisits", rs.getBoolean("accepts_online_visits"));
                m.put("onlineVisitsIntent", rs.getBoolean("accepts_online_visits"));
                // Extra fields from settings jsonb
                var settings = parseJsonObject(rs.getString("settings"));
                m.put("listingAuthorityType", settings.get("listingAuthorityType"));
                m.put("ownershipDeclarationAccepted", Boolean.TRUE.equals(settings.get("ownershipDeclarationAccepted")));
                m.put("licensedEntityName", settings.get("licensedEntityName"));
                m.put("licensedEntityTaxNumber", settings.get("licensedEntityTaxNumber"));
                m.put("licensedEntityRelationship", settings.get("licensedEntityRelationship"));
                // Derived
                m.put("amiStatus", "none");
                m.put("verificationDisplayStatus", "active");
                m.put("publicProfile", Map.of());
                m.put("moderationSummary", loadLatestModeration(rs.getString("id")));
                return (Map<String, Object>) m;
            }).optional().orElse(null);
    }

    private Object loadLatestModeration(String advertiserId) {
        try {
            return jdbc.sql("""
                    SELECT decision, reason_category, public_reason, created_at
                    FROM properia.moderation_decisions
                    WHERE target_id = :id AND target_type = 'advertiser'
                    ORDER BY created_at DESC LIMIT 1
                    """).param("id", UUID.fromString(advertiserId))
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("decision", rs.getString("decision"));
                    m.put("reasonCategory", rs.getString("reason_category"));
                    m.put("publicReason", rs.getString("public_reason"));
                    m.put("decidedAt", rs.getTimestamp("created_at") != null
                        ? rs.getTimestamp("created_at").toInstant().toString() : null);
                    return (Object) m;
                }).optional().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object> parseJsonArray(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String generateSlug(String brandName, UUID id) {
        var base = brandName.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        if (base.isBlank()) base = "anunciante";
        return base + "-" + id.toString().substring(0, 8);
    }

    private void requireAuth(JwtClaims claims) {
        if (claims == null || claims.userId() == null) {
            throw new DomainException("UNAUTHORIZED", "Sessão ausente.", 401);
        }
    }
}
