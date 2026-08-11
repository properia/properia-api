package pt.properia.api.modules.acquisition.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.acquisition.domain.PropertyValuationRequest;
import pt.properia.api.modules.acquisition.infrastructure.PropertyValuationRequestJpaRepository;
import pt.properia.api.modules.auth.infrastructure.AuthEmailService;
import pt.properia.api.modules.crm.domain.Lead;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingestão de um pedido de avaliação vindo da landing pública de angariação.
 *
 * Cria, numa única transação:
 *   - um {@code Lead} de tipo OWNER (sem imóvel — é o que se vai angariar)
 *   - o snapshot em {@code property_valuation_requests} com a estimativa mostrada
 *
 * Os emails são disparados FORA da transação, pelo chamador, via
 * {@link #notifyAfterCommit}. Uma falha do Resend não pode fazer rollback de um
 * lead que a equipa comercial já devia estar a trabalhar.
 */
@Service
public class CreateOwnerValuationRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateOwnerValuationRequestUseCase.class);

    /** Teto de submissões por email em 24h. Acima disto é automação ou engano. */
    private static final int MAX_REQUESTS_PER_EMAIL_PER_DAY = 5;

    /** Tempo mínimo de preenchimento credível do formulário. Abaixo disto é automação. */
    public static final long MIN_FILL_MILLIS = 3_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LeadJpaRepository leadRepo;
    private final PropertyValuationRequestJpaRepository requestRepo;
    private final ValuationEstimateService estimateService;
    private final AcquisitionRoutingService routingService;
    private final AuthEmailService emailService;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    public CreateOwnerValuationRequestUseCase(
            LeadJpaRepository leadRepo,
            PropertyValuationRequestJpaRepository requestRepo,
            ValuationEstimateService estimateService,
            AcquisitionRoutingService routingService,
            AuthEmailService emailService,
            ObjectMapper objectMapper,
            JdbcClient jdbc) {
        this.leadRepo = leadRepo;
        this.requestRepo = requestRepo;
        this.estimateService = estimateService;
        this.routingService = routingService;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    public record Command(
        String addressRaw, String postalCode,
        String district, String municipality, String parish,
        Double latitude, Double longitude,
        String propertyType, Integer bedrooms, BigDecimal usableAreaM2,
        String conditionStatus, Integer floorNumber, Boolean hasElevator, String energyRating,
        String sellingHorizon, Boolean hasAgency, String motivation,
        String contactName, String contactEmail, String contactPhone,
        String consentText, boolean marketingConsent, String consentIp,
        Long formStartedAt, Map<String, Object> utm
    ) {}

    public record Result(
        UUID requestId,
        UUID leadId,
        String publicToken,
        ValuationEstimate estimate,
        boolean routed,
        String verificationCode,
        Command command
    ) {}

    @Transactional
    public Result execute(Command cmd) {
        rejectIfTooFast(cmd.formStartedAt());
        rejectIfAbusive(cmd.contactEmail());

        var estimate = estimateService.estimate(new ValuationInput(
            cmd.propertyType(), "sale",
            cmd.district(), cmd.municipality(), cmd.parish(),
            cmd.bedrooms(), cmd.usableAreaM2(),
            cmd.conditionStatus(), cmd.floorNumber(), cmd.hasElevator(), cmd.energyRating()
        ));

        var advertiserId = routingService
            .routeOwnerLead(cmd.district(), cmd.municipality(), cmd.parish())
            .orElse(null);

        var lead = leadRepo.save(Lead.forOwner(
            advertiserId,
            "owner_landing",
            cmd.contactName(),
            cmd.contactEmail(),
            cmd.contactPhone(),
            buildLeadMessage(cmd),
            writeJson(buildLeadMetadata(cmd, estimate))
        ));

        var now = Instant.now();
        var code = generateCode();

        var request = new PropertyValuationRequest();
        request.setLeadId(lead.getId());
        request.setPublicToken(generateToken());
        request.setAddressRaw(cmd.addressRaw());
        request.setPostalCode(emptyToNull(cmd.postalCode()));
        request.setDistrict(cmd.district());
        request.setMunicipality(cmd.municipality());
        request.setParish(cmd.parish());
        request.setLatitude(cmd.latitude());
        request.setLongitude(cmd.longitude());
        request.setPropertyType(cmd.propertyType());
        request.setBedrooms(cmd.bedrooms());
        request.setUsableAreaM2(cmd.usableAreaM2());
        request.setConditionStatus(emptyToNull(cmd.conditionStatus()));
        request.setFloorNumber(cmd.floorNumber());
        request.setHasElevator(cmd.hasElevator());
        request.setEnergyRating(emptyToNull(cmd.energyRating()));
        request.setSellingHorizon(emptyToNull(cmd.sellingHorizon()));
        request.setHasAgency(cmd.hasAgency());
        request.setMotivation(cmd.motivation());

        request.setEstimateMin(estimate.min());
        request.setEstimateMax(estimate.max());
        request.setEstimatePpm2(estimate.pricePerM2());
        request.setEstimateConfidence(estimate.confidence());
        request.setEstimateSampleSize(estimate.sampleSize());
        request.setEstimateSource(estimate.source());
        request.setEstimateInputs(writeJson(estimate.inputs()));

        request.setConsentGranted(true);
        request.setConsentText(cmd.consentText());
        request.setConsentIp(cmd.consentIp());
        request.setConsentAt(now);
        request.setMarketingConsent(cmd.marketingConsent());
        request.setUtm(writeJson(cmd.utm() != null ? cmd.utm() : Map.of()));
        request.issueContactCode(hashOtp(code), now);

        var saved = requestRepo.save(request);

        return new Result(
            saved.getId(), lead.getId(), saved.getPublicToken(),
            estimate, advertiserId != null, code, cmd
        );
    }

    // ── Efeitos secundários (pós-commit) ──────────────────────────────────────

    /**
     * Emails ao proprietário e ao consultor. Chamado depois do commit — nunca
     * dentro da transação. Falhas são registadas e engolidas: o lead já está
     * guardado e é isso que interessa.
     */
    public void notifyAfterCommit(Result result) {
        var cmd = result.command();

        try {
            emailService.sendValuationContactCode(cmd.contactEmail(), result.verificationCode());
        } catch (Exception e) {
            log.error("Falha ao enviar código de verificação do pedido {}", result.requestId(), e);
        }

        try {
            emailService.sendValuationReport(
                cmd.contactEmail(),
                firstName(cmd.contactName()),
                addressLabel(cmd),
                rangeLabel(result.estimate()),
                result.estimate().scopeLabel(),
                result.estimate().sampleSize(),
                consultantLine(result),
                result.publicToken()
            );
        } catch (Exception e) {
            log.error("Falha ao enviar relatório do pedido {}", result.requestId(), e);
        }

        findAdvertiserEmail(result).ifPresent(to -> {
            try {
                emailService.sendValuationLeadToConsultant(
                    to, cmd.contactName(), addressLabel(cmd),
                    rangeLabel(result.estimate()), horizonLabel(cmd.sellingHorizon()),
                    result.leadId().toString());
            } catch (Exception e) {
                log.error("Falha ao alertar consultor do pedido {}", result.requestId(), e);
            }
        });
    }

    private Optional<String> findAdvertiserEmail(Result result) {
        if (!result.routed()) return Optional.empty();
        return jdbc.sql("""
                SELECT a.email
                FROM properia.leads l
                JOIN properia.advertisers a ON a.id = l.advertiser_id
                WHERE l.id = :leadId AND a.email IS NOT NULL AND a.is_active = true
                """)
            .param("leadId", result.leadId())
            .query(String.class)
            .optional();
    }

    // ── Guardas anti-abuso ────────────────────────────────────────────────────

    private void rejectIfTooFast(Long formStartedAt) {
        if (formStartedAt == null) return;
        long elapsed = System.currentTimeMillis() - formStartedAt;
        // Timestamps futuros ou absurdamente antigos são relógio adulterado.
        if (elapsed < 0 || elapsed < MIN_FILL_MILLIS) {
            throw new DomainException("VALIDATION_ERROR",
                "Pedido inválido. Tente novamente.", 422);
        }
    }

    private void rejectIfAbusive(String email) {
        if (email == null || email.isBlank()) return;
        var since = Instant.now().minus(1, ChronoUnit.DAYS);
        if (requestRepo.countRecentByContactEmail(email, since) >= MAX_REQUESTS_PER_EMAIL_PER_DAY) {
            throw new DomainException("RATE_LIMITED",
                "Já submeteu vários pedidos hoje. Entraremos em contacto em breve.", 429);
        }
    }

    // ── Construção de conteúdo ────────────────────────────────────────────────

    private String buildLeadMessage(Command cmd) {
        var sb = new StringBuilder("Pedido de avaliação submetido em properia.pt/vender.");
        if (cmd.addressRaw() != null && !cmd.addressRaw().isBlank()) {
            sb.append("\nMorada: ").append(cmd.addressRaw());
        }
        if (cmd.sellingHorizon() != null && !cmd.sellingHorizon().isBlank()) {
            sb.append("\nHorizonte de venda: ").append(horizonLabel(cmd.sellingHorizon()));
        }
        if (cmd.hasAgency() != null) {
            sb.append("\nJá tem mediadora: ").append(cmd.hasAgency() ? "sim" : "não");
        }
        if (cmd.motivation() != null && !cmd.motivation().isBlank()) {
            sb.append("\nMotivação: ").append(cmd.motivation());
        }
        return sb.toString();
    }

    private Map<String, Object> buildLeadMetadata(Command cmd, ValuationEstimate estimate) {
        var m = new LinkedHashMap<String, Object>();
        m.put("sourceContext", "owner_valuation_landing");
        m.put("sellingHorizon", cmd.sellingHorizon());
        m.put("hasAgency", cmd.hasAgency());
        m.put("propertyType", cmd.propertyType());
        m.put("municipality", cmd.municipality());
        m.put("parish", cmd.parish());
        m.put("estimateAvailable", estimate.available());
        m.put("estimateMin", estimate.min());
        m.put("estimateMax", estimate.max());
        m.put("estimateConfidence", estimate.confidence());
        if (cmd.utm() != null && !cmd.utm().isEmpty()) {
            m.put("utm", cmd.utm());
        }
        return m;
    }

    private String consultantLine(Result result) {
        if (!result.routed()) {
            return "Um consultor da Properia entrará em contacto consigo brevemente.";
        }
        return "Um consultor vai contactá-lo para agendar uma avaliação presencial gratuita, "
            + "que é a única forma de chegar a um valor rigoroso.";
    }

    static String rangeLabel(ValuationEstimate estimate) {
        if (estimate == null || !estimate.available()) {
            return "a confirmar com o consultor";
        }
        return formatEuro(estimate.min()) + " — " + formatEuro(estimate.max());
    }

    /** Formata em pt-PT sem depender do CLDR do JDK (que já mudou o separador de
     *  milhares de '.' para espaço fino entre versões). */
    private static String formatEuro(BigDecimal value) {
        if (value == null) return "—";
        var symbols = new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
        symbols.setGroupingSeparator('.');
        return new java.text.DecimalFormat("#,##0", symbols).format(value) + " €";
    }

    static String horizonLabel(String horizon) {
        if (horizon == null) return null;
        return switch (horizon) {
            case "immediate" -> "quer vender já";
            case "3m"        -> "nos próximos 3 meses";
            case "6m"        -> "nos próximos 6 meses";
            case "exploring" -> "ainda a explorar";
            default          -> horizon;
        };
    }

    private String addressLabel(Command cmd) {
        if (cmd.addressRaw() != null && !cmd.addressRaw().isBlank()) return cmd.addressRaw();
        if (cmd.parish() != null) return cmd.parish() + ", " + cmd.municipality();
        return cmd.municipality();
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().split("\\s+")[0];
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Metadados nunca podem bloquear a criação do lead — é o mesmo
            // princípio já aplicado em LeadController.
            log.warn("Falha a serializar metadados do pedido de avaliação", e);
            return "{}";
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashOtp(String code) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
