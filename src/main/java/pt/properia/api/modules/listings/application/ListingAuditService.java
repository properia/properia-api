package pt.properia.api.modules.listings.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Regista o que uma edição de anúncio realmente mudou na base de dados.
 *
 * <p>A tabela {@code listing_audit} existe desde a V1 mas nunca teve uma única
 * linha escrita. Isso deixou-nos cegos perante um problema real: o wizard de
 * edição já apagou, por duas vezes, dados que ninguém tinha tocado — a descrição
 * gerada por IA de um anúncio e os dados de rendimento/frações de um prédio. Sem
 * rasto, a única prova era o cliente reparar depois.
 *
 * <p>O diff é calculado sobre a vista {@code getForEdit}, que é exactamente o
 * mesmo vocabulário que o wizard envia de volta no PATCH. Isso permite responder
 * à pergunta que interessa: <em>este campo mudou porque alguém o enviou, ou
 * mudou sozinho?</em> Um campo que passa a null sem a sua chave vir no pedido é
 * dano colateral — e é essa a assinatura que estamos à procura.
 */
@Service
public class ListingAuditService {

    private static final Logger log = LoggerFactory.getLogger(ListingAuditService.class);

    /** Um valor grande na coluna não ajuda a diagnosticar e engorda a tabela. */
    private static final int MAX_VALUE_CHARS = 2_000;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ListingAuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** O que aconteceu a um campo entre o antes e o depois de um PATCH. */
    public record FieldChange(String field, Object before, Object after, boolean sentByClient) {
        /** Tinha conteúdo e ficou vazio — o padrão que destrói dados do cliente. */
        boolean isWipe() {
            return !isEmpty(before) && isEmpty(after);
        }
    }

    /**
     * Escreve o diff numa transação própria. {@code REQUIRES_NEW} é deliberado:
     * a auditoria nunca pode fazer rollback da edição que está a auditar, nem ser
     * arrastada por um rollback dela — se a edição falhar a meio, queremos na
     * mesma saber o que já tinha sido escrito.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPatch(UUID listingId,
                            UUID changedBy,
                            String changeSource,
                            Map<String, Object> before,
                            Map<String, Object> after,
                            java.util.Set<String> requestKeys) {
        try {
            var changes = diff(before, after, requestKeys);
            if (changes.isEmpty()) return;

            var wipes = changes.stream().filter(FieldChange::isWipe).toList();
            var unsent = changes.stream().filter(c -> !c.sentByClient()).map(FieldChange::field).toList();

            // Um campo apagado sem a chave ter vindo no pedido não é uma decisão do
            // utilizador — é um bug. Vai a WARN para aparecer nos logs de produção
            // sem ser preciso ir à tabela.
            var collateralWipes = wipes.stream().filter(c -> !c.sentByClient()).map(FieldChange::field).toList();
            if (!collateralWipes.isEmpty()) {
                log.warn("listing_audit: PATCH ao anúncio {} apagou {} campo(s) que o cliente NÃO enviou: {} "
                         + "(chaves recebidas: {})",
                         listingId, collateralWipes.size(), collateralWipes, requestKeys);
            } else if (!wipes.isEmpty()) {
                log.info("listing_audit: PATCH ao anúncio {} esvaziou {}", listingId,
                         wipes.stream().map(FieldChange::field).toList());
            }

            var beforePayload = new LinkedHashMap<String, Object>();
            var afterPayload = new LinkedHashMap<String, Object>();
            for (var c : changes) {
                beforePayload.put(c.field(), truncate(c.before()));
                afterPayload.put(c.field(), truncate(c.after()));
            }

            var payloadBefore = Map.of("fields", beforePayload);
            var payloadAfter = new LinkedHashMap<String, Object>();
            payloadAfter.put("fields", afterPayload);
            payloadAfter.put("requestKeys", new ArrayList<>(requestKeys));
            payloadAfter.put("unsent", unsent);
            payloadAfter.put("wiped", wipes.stream().map(FieldChange::field).toList());

            jdbc.sql("""
                    INSERT INTO properia.listing_audit
                      (listing_id, event_type, changed_by, change_source, payload_before, payload_after)
                    VALUES (:lid, :event, :by, :source, :before::jsonb, :after::jsonb)
                    """)
                .param("lid", listingId)
                .param("event", collateralWipes.isEmpty() ? "patch" : "patch.collateral_wipe")
                .param("by", changedBy)
                .param("source", changeSource != null ? changeSource : "unknown")
                .param("before", json.writeValueAsString(payloadBefore))
                .param("after", json.writeValueAsString(payloadAfter))
                .update();
        } catch (Exception e) {
            // Perder uma linha de auditoria é mau; rebentar a edição do cliente por
            // causa dela é pior.
            log.warn("listing_audit: falhou o registo do PATCH ao anúncio {}: {}", listingId, e.toString());
        }
    }

    /**
     * Campos que o servidor mexe por definição em qualquer edição. Registá-los
     * enche a coluna {@code unsent} — que existe para apontar dano colateral — com
     * entradas que são sempre esperadas, e a lista deixa de querer dizer alguma coisa.
     */
    private static final java.util.Set<String> DERIVED_FIELDS = java.util.Set.of(
        "updatedAt", "dataEntryAt", "publishedAt"
    );

    static List<FieldChange> diff(Map<String, Object> before,
                                  Map<String, Object> after,
                                  java.util.Set<String> requestKeys) {
        var changes = new ArrayList<FieldChange>();
        if (before == null || after == null) return changes;

        var fields = new java.util.LinkedHashSet<String>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());
        fields.removeAll(DERIVED_FIELDS);

        for (var field : fields) {
            var oldValue = before.get(field);
            var newValue = after.get(field);
            if (sameValue(oldValue, newValue)) continue;
            changes.add(new FieldChange(field, oldValue, newValue, requestKeys.contains(field)));
        }
        return changes;
    }

    /**
     * Compara pelo valor que o utilizador vê, não pelo tipo Java. O mesmo número
     * atravessa esta camada ora como {@code BigDecimal}, ora como String, ora como
     * Integer conforme venha da entidade ou do corpo do pedido — comparar com
     * {@code equals} produziria um diff cheio de mudanças que não são mudanças, e
     * o ruído esconderia o que interessa.
     */
    static boolean sameValue(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (isEmpty(a) && isEmpty(b)) return true;
        if (a == null || b == null) return false;

        if (a instanceof Number && b instanceof Number
            || a instanceof Number && b instanceof String
            || a instanceof String && b instanceof Number) {
            try {
                return new java.math.BigDecimal(a.toString().trim())
                    .compareTo(new java.math.BigDecimal(b.toString().trim())) == 0;
            } catch (NumberFormatException ignored) {
                // Não era mesmo um número; cai para a comparação textual.
            }
        }
        if (a instanceof Collection<?> || b instanceof Collection<?>) return false;
        return a.toString().equals(b.toString());
    }

    static boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    private static Object truncate(Object v) {
        if (v instanceof String s && s.length() > MAX_VALUE_CHARS) {
            return s.substring(0, MAX_VALUE_CHARS) + "…[+" + (s.length() - MAX_VALUE_CHARS) + " chars]";
        }
        return v;
    }
}
