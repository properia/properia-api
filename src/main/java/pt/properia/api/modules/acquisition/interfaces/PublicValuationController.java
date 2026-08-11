package pt.properia.api.modules.acquisition.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.acquisition.application.CreateOwnerValuationRequestUseCase;
import pt.properia.api.modules.acquisition.application.ValuationEstimate;
import pt.properia.api.modules.acquisition.application.ValuationEstimateService;
import pt.properia.api.modules.acquisition.application.ValuationInput;
import pt.properia.api.modules.acquisition.application.VerifyValuationContactUseCase;
import pt.properia.api.modules.acquisition.domain.PropertyValuationRequest;
import pt.properia.api.modules.acquisition.infrastructure.PropertyValuationRequestJpaRepository;
import pt.properia.api.modules.acquisition.interfaces.request.CreateValuationRequestRequest;
import pt.properia.api.modules.acquisition.interfaces.request.EstimateValuationRequest;
import pt.properia.api.modules.acquisition.interfaces.request.VerifyValuationContactRequest;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints públicos da landing de angariação (/vender).
 *
 * Sob /api/public/** para herdar o permitAll já configurado em SecurityConfig e
 * passar pelo proxy /api/* do frontend. Nenhum destes endpoints exige sessão —
 * quem os chama é um proprietário anónimo.
 *
 * Defesa em profundidade, por ordem: honeypot e tempo de preenchimento (grátis,
 * apanha bots simples) → rate limit por IP (RateLimitingFilter, tiers
 * PUBLIC_FORM e ESTIMATE) → teto por email em 24h (use case) → verificação por
 * código antes de o contacto ser tratado como fiável.
 */
@RestController
@RequestMapping("/api/public/valuation")
public class PublicValuationController {

    private static final Logger log = LoggerFactory.getLogger(PublicValuationController.class);

    private final ValuationEstimateService estimateService;
    private final CreateOwnerValuationRequestUseCase createRequest;
    private final VerifyValuationContactUseCase verifyContactUseCase;
    private final PropertyValuationRequestJpaRepository requestRepo;
    private final LeadJpaRepository leadRepo;
    private final pt.properia.api.modules.auth.infrastructure.AuthEmailService emailService;

    public PublicValuationController(
            ValuationEstimateService estimateService,
            CreateOwnerValuationRequestUseCase createRequest,
            VerifyValuationContactUseCase verifyContactUseCase,
            PropertyValuationRequestJpaRepository requestRepo,
            LeadJpaRepository leadRepo,
            pt.properia.api.modules.auth.infrastructure.AuthEmailService emailService) {
        this.estimateService = estimateService;
        this.createRequest = createRequest;
        this.verifyContactUseCase = verifyContactUseCase;
        this.requestRepo = requestRepo;
        this.leadRepo = leadRepo;
        this.emailService = emailService;
    }

    // ── Estimativa (sem dados pessoais) ───────────────────────────────────────

    /**
     * Calculadora do formulário. Chamado a cada passo do wizard, por isso é
     * deliberadamente sem estado e sem PII: nesta altura ainda não há
     * consentimento para tratar dados do proprietário.
     */
    @PostMapping("/estimate")
    public ResponseEntity<?> estimate(@Valid @RequestBody EstimateValuationRequest req) {
        var estimate = estimateService.estimate(new ValuationInput(
            req.propertyType(),
            req.businessType(),
            req.district(),
            req.municipality(),
            req.parish(),
            req.bedrooms(),
            req.usableAreaM2(),
            req.conditionStatus(),
            req.floorNumber(),
            req.hasElevator(),
            req.energyRating()
        ));

        return ResponseEntity.ok(Map.of("data", toPublicEstimate(estimate)));
    }

    // ── Submissão do pedido ───────────────────────────────────────────────────

    @PostMapping("/requests")
    public ResponseEntity<?> createRequest(@Valid @RequestBody CreateValuationRequestRequest req,
                                           HttpServletRequest httpRequest) {

        var result = createRequest.execute(new CreateOwnerValuationRequestUseCase.Command(
            req.addressRaw(), req.postalCode(),
            req.district(), req.municipality(), req.parish(),
            req.latitude(), req.longitude(),
            req.propertyType(), req.bedrooms(), req.usableAreaM2(),
            req.conditionStatus(), req.floorNumber(), req.hasElevator(), req.energyRating(),
            req.sellingHorizon(), req.hasAgency(), req.motivation(),
            req.contactName(), req.contactEmail(), req.contactPhone(),
            req.consentText(), Boolean.TRUE.equals(req.marketingConsent()),
            resolveClientIp(httpRequest),
            req.formStartedAt(), req.utm()
        ));

        // Fora da transação: uma falha do fornecedor de email não pode desfazer
        // um lead que a equipa comercial já devia estar a trabalhar.
        createRequest.notifyAfterCommit(result);

        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", result.requestId());
        body.put("publicToken", result.publicToken());
        body.put("verificationRequired", true);
        body.put("estimate", toPublicEstimate(result.estimate()));

        return ResponseEntity.status(201).body(Map.of("data", body));
    }

    // ── Verificação do contacto ───────────────────────────────────────────────

    /**
     * Consome o código enviado por email e marca o lead como contacto
     * verificado. Enquanto isto não acontecer, o lead existe mas não deve
     * consumir tempo da equipa comercial.
     */
    // NOTA: este método NÃO pode ser @Transactional. O registo de uma tentativa
    // falhada tem de comitar antes de a exceção de "código inválido" subir; com
    // uma transação a envolver tudo, o rollback desfazia o incremento e o
    // bloqueio ao fim de 5 tentativas nunca disparava. Ver
    // VerifyValuationContactUseCase.
    @PostMapping("/requests/{id}/verify")
    public ResponseEntity<?> verifyContact(@PathVariable UUID id,
                                           @Valid @RequestBody VerifyValuationContactRequest req) {
        var request = requestRepo.findById(id)
            .orElseThrow(() -> DomainException.notFound("Pedido não encontrado."));

        if (request.isContactVerified()) {
            return ResponseEntity.ok(Map.of("data", Map.of("verified", true)));
        }

        var now = Instant.now();

        if (request.hasExhaustedAttempts()) {
            throw new DomainException("FORBIDDEN",
                "Excedeu o número de tentativas. Peça um novo código.", 403);
        }

        if (request.isCodeExpired(now)) {
            throw new DomainException("CONFLICT", "O código expirou. Peça um novo código.", 409);
        }

        if (!CreateOwnerValuationRequestUseCase.hashOtp(req.code())
                .equals(request.getContactCodeHash())) {
            verifyContactUseCase.registerFailedAttempt(id);
            throw new DomainException("VALIDATION_ERROR", "Código inválido.", 422);
        }

        verifyContactUseCase.markVerified(id, now);

        return ResponseEntity.ok(Map.of("data", Map.of("verified", true)));
    }

    /** Reenvio do código, com o mesmo cooldown do fluxo de visitas. */
    @PostMapping("/requests/{id}/resend-code")
    public ResponseEntity<?> resendCode(@PathVariable UUID id) {
        var request = requestRepo.findById(id)
            .orElseThrow(() -> DomainException.notFound("Pedido não encontrado."));

        if (request.isContactVerified()) {
            return ResponseEntity.ok(Map.of("data", Map.of("sent", false, "verified", true)));
        }

        var now = Instant.now();
        var cooldown = request.resendCooldownSeconds(now);
        if (cooldown > 0) {
            throw new DomainException("CONFLICT",
                "Espere " + cooldown + "s antes de pedir um novo código.", 409);
        }

        var code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        verifyContactUseCase.issueNewCode(id, CreateOwnerValuationRequestUseCase.hashOtp(code), now);

        var email = leadRepo.findById(request.getLeadId())
            .map(lead -> lead.getContactEmail())
            .orElse(null);

        if (email != null) {
            try {
                emailService.sendValuationContactCode(email, code);
            } catch (Exception e) {
                log.error("Falha ao reenviar código do pedido {}", id, e);
            }
        }

        return ResponseEntity.ok(Map.of("data", Map.of(
            "sent", true, "cooldownSeconds", PropertyValuationRequest.CODE_RESEND_COOLDOWN_SECONDS)));
    }

    // ── Relatório por token ───────────────────────────────────────────────────

    /**
     * Relatório acessível pelo link enviado por email. Usa o token opaco e nunca
     * o id interno — um link partilhado não pode servir para enumerar pedidos.
     */
    @GetMapping("/report/{token}")
    public ResponseEntity<?> report(@PathVariable String token) {
        var request = requestRepo.findByPublicToken(token)
            .orElseThrow(() -> DomainException.notFound("Relatório não encontrado."));

        var body = new LinkedHashMap<String, Object>();
        body.put("addressRaw", request.getAddressRaw());
        body.put("municipality", request.getMunicipality());
        body.put("parish", request.getParish());
        body.put("propertyType", request.getPropertyType());
        body.put("bedrooms", request.getBedrooms());
        body.put("usableAreaM2", request.getUsableAreaM2());
        body.put("estimateMin", request.getEstimateMin());
        body.put("estimateMax", request.getEstimateMax());
        body.put("estimatePricePerM2", request.getEstimatePpm2());
        body.put("confidence", request.getEstimateConfidence());
        body.put("sampleSize", request.getEstimateSampleSize());
        body.put("source", request.getEstimateSource());
        body.put("contactVerified", request.isContactVerified());
        body.put("createdAt", request.getCreatedAt());
        body.put("disclaimer", DISCLAIMER);

        return ResponseEntity.ok(Map.of("data", body));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String DISCLAIMER =
        "Estimativa indicativa gerada a partir de dados de mercado disponíveis na Properia. "
        + "Não constitui avaliação imobiliária certificada nos termos da Lei n.º 153/2015. "
        + "Para um valor rigoroso é necessária uma visita ao imóvel.";

    /**
     * Projeção pública da estimativa. O mapa `inputs` fica de fora
     * deliberadamente: serve auditoria interna, não é para consumo do cliente.
     */
    private Map<String, Object> toPublicEstimate(ValuationEstimate estimate) {
        var m = new LinkedHashMap<String, Object>();
        m.put("available", estimate.available());
        m.put("min", estimate.min());
        m.put("max", estimate.max());
        m.put("pricePerM2", estimate.pricePerM2());
        m.put("confidence", estimate.confidence());
        m.put("sampleSize", estimate.sampleSize());
        m.put("source", estimate.source());
        m.put("scopeLabel", estimate.scopeLabel());
        m.put("disclaimer", DISCLAIMER);
        return m;
    }

    /** Mesma resolução do RateLimitingFilter: Cloudflare primeiro, depois XFF. */
    private static String resolveClientIp(HttpServletRequest request) {
        var cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();

        var xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();

        return request.getRemoteAddr();
    }
}
