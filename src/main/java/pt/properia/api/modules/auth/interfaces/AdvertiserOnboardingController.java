package pt.properia.api.modules.auth.interfaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.auth.application.AuthRepository;
import pt.properia.api.modules.billing.application.BillingService;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;
import pt.properia.api.shared.infrastructure.web.jwt.JwtProperties;
import pt.properia.api.shared.infrastructure.web.jwt.JwtService;

import java.util.*;

@RestController
@RequestMapping("/api/advertisers/onboarding")
public class AdvertiserOnboardingController {

    private static final String SESSION_COOKIE = "properia_session";

    private static final Set<String> VALID_ADVERTISER_TYPES = Set.of(
        "private_owner", "consultant", "agency", "promoter", "developer", "bank_asset_manager");
    private static final Set<String> VALID_STEPS = Set.of(
        "intent", "basic_profile", "commercial_identity", "market_scope", "first_listing", "done");
    private static final Set<String> VALID_LISTING_AUTHORITY_TYPES = Set.of(
        "own_entity", "third_party", "both");
    private static final Set<String> VALID_LICENSED_ENTITY_RELATIONSHIPS = Set.of(
        "license_holder", "associated_consultant", "authorized_representative");
    // Mesma lista de distritos do wizard FE (PT_DISTRICTS) — inclui as regiões autónomas.
    private static final Set<String> VALID_DISTRICTS = Set.of(
        "Aveiro", "Beja", "Braga", "Bragança", "Castelo Branco", "Coimbra",
        "Évora", "Faro", "Guarda", "Leiria", "Lisboa", "Portalegre",
        "Porto", "Santarém", "Setúbal", "Viana do Castelo", "Vila Real", "Viseu",
        "Madeira", "Açores");
    private static final java.util.regex.Pattern EMAIL_PATTERN =
        java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final java.util.regex.Pattern PHONE_PATTERN =
        java.util.regex.Pattern.compile("^[+0-9()\\-\\s/]{9,20}$");

    private static final Logger log = LoggerFactory.getLogger(AdvertiserOnboardingController.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BillingService billingService;
    private final AuthRepository authRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;

    public AdvertiserOnboardingController(JdbcClient jdbc, ObjectMapper objectMapper,
                                          BillingService billingService,
                                          AuthRepository authRepository,
                                          JwtService jwtService,
                                          JwtProperties jwtProps) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.billingService = billingService;
        this.authRepository = authRepository;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
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
        var advertiserTypeSelected = (String) adv.get("advertiserTypeSelected");

        // ── Mudança de tipo de anunciante ─────────────────────────────────────
        // Sem isto, quem escolhia o tipo errado no arranque ficava preso para
        // sempre (o /start devolve 409 se já existe advertiser). Só permitimos
        // mudar enquanto a identidade comercial não foi concluída — depois disso
        // os dados fiscais/AMI já foram declarados para o tipo anterior.
        // Nota: os benefícios de arranque (trial/créditos) NÃO são reatribuídos
        // ao mudar de tipo, para não permitir farmar trials trocando de tipo.
        if (body.get("advertiserType") != null) {
            var newType = body.get("advertiserType").toString();
            if (!VALID_ADVERTISER_TYPES.contains(newType)) {
                throw new DomainException("INVALID_ADVERTISER_TYPE", "Tipo de anunciante inválido.", 422);
            }
            var completedRaw = adv.get("completedSteps");
            var identityDone = completedRaw instanceof List<?> list && list.contains("commercial_identity");
            if (identityDone) {
                throw new DomainException("TYPE_CHANGE_LOCKED",
                    "Já não é possível mudar o tipo de anunciante depois de concluir a identidade comercial. Contacta o suporte.", 422);
            }
            if (!newType.equals(advertiserTypeSelected)) {
                jdbc.sql("UPDATE properia.advertisers SET advertiser_type = :t::properia.advertiser_type, updated_at = now() WHERE id = :id")
                    .param("t", newType).param("id", advertiserId).update();
                jdbc.sql("UPDATE properia.advertiser_onboarding SET advertiser_type_selected = :t::properia.advertiser_type, updated_at = now() WHERE advertiser_id = :id")
                    .param("t", newType).param("id", advertiserId).update();
                advertiserTypeSelected = newType;
            }
        }

        // ── Validação server-side ─────────────────────────────────────────────
        // O wizard valida no cliente, mas nada impedia um PATCH direto de gravar
        // valores inválidos — incluindo casts de enum que rebentavam com 500 e
        // URLs javascript: que ficariam no perfil público (XSS via href).
        if (body.get("stepCurrent") != null && !VALID_STEPS.contains(body.get("stepCurrent").toString())) {
            throw new DomainException("INVALID_STEP", "Etapa de onboarding inválida.", 422);
        }
        if (body.get("markStepCompleted") != null && !VALID_STEPS.contains(body.get("markStepCompleted").toString())) {
            throw new DomainException("INVALID_STEP", "Etapa de onboarding inválida.", 422);
        }
        if (body.get("brandName") != null) {
            var name = body.get("brandName").toString().strip();
            if (name.length() < 2 || name.length() > 160) {
                throw new DomainException("INVALID_BRAND_NAME",
                    "O nome comercial deve ter entre 2 e 160 caracteres.", 422);
            }
        }
        if (body.get("email") != null && !body.get("email").toString().isBlank()
            && !EMAIL_PATTERN.matcher(body.get("email").toString().strip()).matches()) {
            throw new DomainException("INVALID_EMAIL", "Indica um email válido (ex.: nome@empresa.pt).", 422);
        }
        if (body.get("phone") != null && !body.get("phone").toString().isBlank()
            && !PHONE_PATTERN.matcher(body.get("phone").toString().strip()).matches()) {
            throw new DomainException("INVALID_PHONE", "Indica um telefone válido (ex.: +351 912 345 678).", 422);
        }
        if (body.get("websiteUrl") != null && !body.get("websiteUrl").toString().isBlank()) {
            var url = body.get("websiteUrl").toString().strip().toLowerCase();
            if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 500) {
                throw new DomainException("INVALID_WEBSITE_URL",
                    "O website deve começar com https:// (ex.: https://agencia.pt).", 422);
            }
        }
        if (body.get("businessCountry") != null && !body.get("businessCountry").toString().isBlank()
            && !"PT".equalsIgnoreCase(body.get("businessCountry").toString().strip())) {
            throw new DomainException("INVALID_COUNTRY",
                "Neste lançamento, suportamos apenas anunciantes com identificação fiscal portuguesa.", 422);
        }
        if (body.get("businessPostalCode") != null && !body.get("businessPostalCode").toString().isBlank()
            && !body.get("businessPostalCode").toString().strip().matches("\\d{4}-\\d{3}")) {
            throw new DomainException("INVALID_POSTAL_CODE", "Usa o formato XXXX-XXX (ex.: 1000-001).", 422);
        }
        if (body.get("listingAuthorityType") != null && !body.get("listingAuthorityType").toString().isBlank()
            && !VALID_LISTING_AUTHORITY_TYPES.contains(body.get("listingAuthorityType").toString())) {
            throw new DomainException("INVALID_INPUT", "Tipo de autoridade de anúncio inválido.", 422);
        }
        if (body.get("licensedEntityRelationship") != null && !body.get("licensedEntityRelationship").toString().isBlank()
            && !VALID_LICENSED_ENTITY_RELATIONSHIPS.contains(body.get("licensedEntityRelationship").toString())) {
            throw new DomainException("INVALID_INPUT", "Relação com a entidade licenciada inválida.", 422);
        }
        if (body.get("licensedEntityTaxNumber") != null && !body.get("licensedEntityTaxNumber").toString().isBlank()
            && !isValidPortugueseTaxNumber(body.get("licensedEntityTaxNumber").toString())) {
            throw new DomainException("INVALID_LICENSED_ENTITY_TAX_NUMBER",
                "O NIF/NIPC da entidade licenciada não é válido.", 422);
        }
        if (body.containsKey("serviceDistricts")) {
            if (!(body.get("serviceDistricts") instanceof List<?> districts) || districts.size() > VALID_DISTRICTS.size()
                || !districts.stream().allMatch(d -> d instanceof String s && VALID_DISTRICTS.contains(s))) {
                throw new DomainException("INVALID_INPUT", "Lista de distritos de atuação inválida.", 422);
            }
        }
        if (body.containsKey("propertySpecialties")) {
            if (!(body.get("propertySpecialties") instanceof List<?> specs) || specs.size() > 20
                || !specs.stream().allMatch(s -> s instanceof String str && str.length() <= 60)) {
                throw new DomainException("INVALID_INPUT", "Lista de especialidades inválida.", 422);
            }
        }
        if (body.get("taxNumber") != null) {
            var tax = body.get("taxNumber").toString();
            if (!tax.isBlank() && !isValidPortugueseTaxNumber(tax)) {
                throw new DomainException("INVALID_TAX_NUMBER",
                    "NIF/NIPC inválido. Introduz um número português válido com 9 dígitos.", 422);
            }
        }
        if (body.get("licenseNumber") != null) {
            var license = body.get("licenseNumber").toString();
            if (!license.isBlank() && requiresAmiRegistration(advertiserTypeSelected) && !isValidAmiLicenseNumber(license)) {
                throw new DomainException("INVALID_AMI_LICENSE",
                    "Número AMI inválido. Usa o formato AMI 6600 ou 6600.", 422);
            }
        }
        // Ao concluir a etapa "commercial_identity", exigir NIF válido sempre e AMI válido
        // para agência/consultor — sem isto, um PATCH direto com stepCurrent="done" saltava
        // esta etapa por completo e chegava a publicar anúncios sem identidade fiscal/legal.
        if ("commercial_identity".equals(body.get("markStepCompleted"))) {
            var effectiveTax = body.containsKey("taxNumber")
                ? stringOrNull(body.get("taxNumber")) : (String) adv.get("taxNumber");
            if (!isValidPortugueseTaxNumber(effectiveTax != null ? effectiveTax : "")) {
                throw new DomainException("INVALID_TAX_NUMBER",
                    "É necessário um NIF/NIPC válido para concluir esta etapa.", 422);
            }
            if (requiresAmiRegistration(advertiserTypeSelected)) {
                var effectiveLicense = body.containsKey("licenseNumber")
                    ? stringOrNull(body.get("licenseNumber")) : (String) adv.get("licenseNumber");
                if (!isValidAmiLicenseNumber(effectiveLicense != null ? effectiveLicense : "")) {
                    throw new DomainException("INVALID_AMI_LICENSE",
                        "É necessário um número AMI válido para concluir esta etapa (obrigatório por lei para mediação imobiliária).", 422);
                }
            }
        }

        // Regras da etapa "market_scope" — antes só o cliente as aplicava; um PATCH
        // direto concluía a etapa sem a declaração de propriedade (particulares) ou
        // sem indicar a autoridade de anúncio (promotor/developer/asset manager).
        if ("market_scope".equals(body.get("markStepCompleted"))) {
            if ("private_owner".equals(advertiserTypeSelected)) {
                boolean accepted = body.containsKey("ownershipDeclarationAccepted")
                    ? Boolean.TRUE.equals(body.get("ownershipDeclarationAccepted"))
                    : Boolean.TRUE.equals(adv.get("ownershipDeclarationAccepted"));
                if (!accepted) {
                    throw new DomainException("OWNERSHIP_DECLARATION_REQUIRED",
                        "Confirma que tens autorização para anunciar o imóvel antes de continuar.", 422);
                }
            }
            if (Set.of("promoter", "developer", "bank_asset_manager").contains(advertiserTypeSelected)) {
                var authority = body.containsKey("listingAuthorityType")
                    ? stringOrNull(body.get("listingAuthorityType"))
                    : stringOrNull(adv.get("listingAuthorityType"));
                if (authority == null || authority.isBlank()) {
                    throw new DomainException("LISTING_AUTHORITY_REQUIRED",
                        "Indica se vais anunciar imóveis próprios ou de terceiros.", 422);
                }
                // Imóveis de terceiros exigem enquadramento AMI (Lei n.º 15/2013):
                // licença própria válida OU entidade licenciada identificada.
                if (!"own_entity".equals(authority)) {
                    var license = body.containsKey("licenseNumber")
                        ? stringOrNull(body.get("licenseNumber")) : (String) adv.get("licenseNumber");
                    var entityName = body.containsKey("licensedEntityName")
                        ? stringOrNull(body.get("licensedEntityName")) : stringOrNull(adv.get("licensedEntityName"));
                    var entityTax = body.containsKey("licensedEntityTaxNumber")
                        ? stringOrNull(body.get("licensedEntityTaxNumber")) : stringOrNull(adv.get("licensedEntityTaxNumber"));
                    boolean hasOwnAmi = license != null && isValidAmiLicenseNumber(license);
                    boolean hasEntity = entityName != null && !entityName.isBlank()
                        && entityTax != null && isValidPortugueseTaxNumber(entityTax);
                    if (!hasOwnAmi && !hasEntity) {
                        throw new DomainException("THIRD_PARTY_AUTHORITY_REQUIRED",
                            "Para anunciar imóveis de terceiros, indica a tua licença AMI ou a mediadora licenciada (nome e NIF) que te representa.", 422);
                    }
                }
            }
        }

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

    @Transactional
    @PostMapping("/start")
    public ResponseEntity<?> startOnboarding(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal JwtClaims claims,
                                             HttpServletResponse response) {
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

        // Verify user exists in app_users before creating advertiser
        boolean userExists = jdbc.sql("SELECT 1 FROM properia.app_users WHERE id = :uid LIMIT 1")
            .param("uid", claims.userId())
            .query((rs, n) -> true).optional().isPresent();
        if (!userExists) {
            throw new DomainException("USER_NOT_FOUND", "Utilizador não encontrado. Faz login novamente.", 404);
        }

        var advertiserType = body.getOrDefault("advertiserType", "private_owner").toString();
        // Sem validar o enum aqui, o cast ::properia.advertiser_type rebentava com
        // um erro SQL (500) em vez de uma resposta 422 legível.
        if (!VALID_ADVERTISER_TYPES.contains(advertiserType)) {
            throw new DomainException("INVALID_ADVERTISER_TYPE", "Tipo de anunciante inválido.", 422);
        }
        var brandName = body.containsKey("brandName") && body.get("brandName") != null
            ? body.get("brandName").toString().strip() : claims.name();
        if (brandName == null || brandName.strip().length() < 2 || brandName.strip().length() > 160) {
            throw new DomainException("INVALID_BRAND_NAME",
                "O nome comercial deve ter entre 2 e 160 caracteres.", 422);
        }
        brandName = brandName.strip();
        var id = UUID.randomUUID();
        var slug = generateSlug(brandName, id);

        // NOTA (não corrigido nesta sessão): todo anunciante nasce com 'verified_basic' sem
        // qualquer verificação real ter ocorrido — ver PROPERIA_READINESS para detalhe. Não
        // mudei para 'pending_review' porque não existe NENHUM endpoint/admin que tire um
        // advertiser desse estado; isso deixaria todos presos "em revisão" para sempre, o que
        // é pior. Corrigir a sério exige uma fila/UI de revisão (decisão de produto à parte).
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

        // Contas profissionais (tudo exceto particular) recebem 6 meses de trial
        // Business; particulares recebem 100 créditos de boas-vindas no Starter.
        if (!"private_owner".equals(advertiserType)) {
            try { billingService.activateTrial(id); }
            catch (Exception e) { log.warn("Onboarding: falha ao ativar trial para advertiser {}: {}", id, e.getMessage()); }
        } else {
            try { billingService.grantWelcomeCredits(id, 100); }
            catch (Exception e) { log.warn("Onboarding: falha ao conceder créditos de boas-vindas para advertiser {}: {}", id, e.getMessage()); }
        }

        var result = loadOnboarding(claims.userId());

        // Refresh JWT cookie so hasAdvertiserAccess reflects the new state immediately
        try {
            var session = authRepository.buildSessionUser(claims.userId());
            var newToken = jwtService.generateToken(new JwtClaims(
                session.sub(), session.email(), session.name(), session.role(),
                session.avatarUrl(), session.hasAdvertiserAccess(),
                session.activeAdvertiserId(), claims.sessionId()
            ));
            response.addHeader("Set-Cookie",
                SESSION_COOKIE + "=" + newToken
                    + "; Path=/"
                    + "; HttpOnly"
                    + (jwtProps.isCookieSecure() ? "; Secure" : "")
                    + "; SameSite=Lax"
                    + "; Max-Age=" + jwtProps.getTtlSeconds()
                    + (jwtProps.getCookieDomain() != null && !jwtProps.getCookieDomain().isBlank()
                        ? "; Domain=" + jwtProps.getCookieDomain() : "")
            );
        } catch (Exception e) {
            log.warn("Onboarding: falha ao renovar cookie de sessão após criar advertiser {}: {}", id, e.getMessage());
        }

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
                WHERE au.user_id = :uid
                ORDER BY CASE WHEN au.membership_role = 'owner' THEN 0 ELSE 1 END, a.created_at DESC
                LIMIT 1
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

    private String stringOrNull(Object value) {
        return value != null ? value.toString() : null;
    }

    private boolean requiresAmiRegistration(String advertiserType) {
        return "agency".equals(advertiserType) || "consultant".equals(advertiserType);
    }

    // Porto fiel de isValidPortugueseTaxNumber (shared/advertiser-verification.ts) — módulo
    // 11 sobre os primeiros 8 dígitos com pesos 9..2.
    private boolean isValidPortugueseTaxNumber(String value) {
        if (value == null) return false;
        var normalized = value.strip();
        if (!normalized.matches("\\d{9}")) return false;
        int checksum = 0;
        for (int i = 0; i < 8; i++) {
            checksum += (normalized.charAt(i) - '0') * (9 - i);
        }
        int remainder = checksum % 11;
        int checkDigit = remainder < 2 ? 0 : 11 - remainder;
        return (normalized.charAt(8) - '0') == checkDigit;
    }

    // Porto fiel de isValidAmiLicenseNumber — aceita "AMI 6600", "AMI-6600" ou só "6600".
    private static final java.util.regex.Pattern AMI_LICENSE_PATTERN =
        java.util.regex.Pattern.compile("^(?:AMI[\\s-]*)?\\d{3,6}$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean isValidAmiLicenseNumber(String value) {
        if (value == null) return false;
        return AMI_LICENSE_PATTERN.matcher(value.strip()).matches();
    }
}
