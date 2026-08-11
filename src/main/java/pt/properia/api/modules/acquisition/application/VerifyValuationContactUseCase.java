package pt.properia.api.modules.acquisition.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.acquisition.infrastructure.PropertyValuationRequestJpaRepository;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistência do resultado de uma tentativa de verificação de contacto.
 *
 * Existe separado do controller por uma razão concreta e não estética: o
 * registo de uma tentativa FALHADA tem de sobreviver à exceção que devolve o
 * erro ao cliente. Com o método de verificação inteiro anotado @Transactional, o
 * incremento do contador era gravado e logo desfeito pelo rollback da exceção —
 * o contador ficava eternamente a zero e o bloqueio ao fim de 5 tentativas nunca
 * disparava, deixando o código de 6 dígitos aberto a força bruta.
 *
 * Cada método aqui é a sua própria transação, chamada a partir de um controller
 * NÃO transacional: a falha comita antes de a exceção subir.
 */
@Service
public class VerifyValuationContactUseCase {

    private final PropertyValuationRequestJpaRepository requestRepo;
    private final LeadJpaRepository leadRepo;

    public VerifyValuationContactUseCase(
            PropertyValuationRequestJpaRepository requestRepo,
            LeadJpaRepository leadRepo) {
        this.requestRepo = requestRepo;
        this.leadRepo = leadRepo;
    }

    /** Comita o incremento antes de o chamador lançar o erro de código inválido. */
    @Transactional
    public void registerFailedAttempt(UUID requestId) {
        requestRepo.findById(requestId).ifPresent(request -> {
            request.registerFailedAttempt();
            requestRepo.save(request);
        });
    }

    /** Marca o pedido e o lead como verificados — atomicamente, aqui sim. */
    @Transactional
    public void markVerified(UUID requestId, Instant now) {
        requestRepo.findById(requestId).ifPresent(request -> {
            request.markContactVerified(now);
            requestRepo.save(request);

            leadRepo.findById(request.getLeadId()).ifPresent(lead -> {
                lead.setContactVerified(true);
                leadRepo.save(lead);
            });
        });
    }

    /** Emite um código novo. Transação própria pelo mesmo motivo. */
    @Transactional
    public void issueNewCode(UUID requestId, String codeHash, Instant now) {
        requestRepo.findById(requestId).ifPresent(request -> {
            request.issueContactCode(codeHash, now);
            requestRepo.save(request);
        });
    }
}
