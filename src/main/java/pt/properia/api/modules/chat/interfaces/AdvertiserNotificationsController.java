package pt.properia.api.modules.chat.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.crm.infrastructure.LeadJpaRepository;
import pt.properia.api.modules.crm.infrastructure.VisitJpaRepository;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/notifications")
public class AdvertiserNotificationsController {

    private final LeadJpaRepository leadRepo;
    private final VisitJpaRepository visitRepo;

    public AdvertiserNotificationsController(LeadJpaRepository leadRepo, VisitJpaRepository visitRepo) {
        this.leadRepo = leadRepo;
        this.visitRepo = visitRepo;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = requireAdvertiserId(claims);
        var items = buildNotifications(advertiserId);
        long unread = items.stream().filter(n -> n.get("readAt") == null).count();
        return ResponseEntity.ok(Map.of("data", Map.of(
            "items", items,
            "unreadCount", unread
        )));
    }

    private List<Map<String, Object>> buildNotifications(UUID advertiserId) {
        var items = new ArrayList<Map<String, Object>>();

        // New leads as notifications
        leadRepo.findByAdvertiserIdOrderByCreatedAtDesc(advertiserId).stream()
            .limit(20)
            .forEach(lead -> {
                var title = "new".equals(lead.getStage())
                    ? "Novo lead recebido"
                    : "Lead actualizado";
                var description = lead.getContactName() != null
                    ? "Contacto: " + lead.getContactName()
                    : "Lead sem nome";
                items.add(Map.of(
                    "id", "lead-" + lead.getId(),
                    "kind", "lead",
                    "severity", "new".equals(lead.getStage()) ? "action_required" : "info",
                    "title", title,
                    "description", description,
                    "createdAt", lead.getCreatedAt().toString(),
                    "href", "/anunciante/compradores/" + lead.getId()
                ));
            });

        // Visits as notifications
        visitRepo.findByAdvertiserIdOrderByStartsAtDesc(advertiserId).stream()
            .limit(20)
            .filter(v -> "requested".equals(v.getStatus()) || "confirmed".equals(v.getStatus()))
            .forEach(visit -> {
                var isRequested = "requested".equals(visit.getStatus());
                items.add(Map.of(
                    "id", "visit-" + visit.getId(),
                    "kind", "visit",
                    "severity", isRequested ? "action_required" : "info",
                    "title", isRequested ? "Pedido de visita" : "Visita confirmada",
                    "description", "Visita agendada para " + visit.getStartsAt().toString().substring(0, 16),
                    "createdAt", visit.getCreatedAt().toString(),
                    "href", "/anunciante/visitas"
                ));
            });

        items.sort(Comparator.comparing(
            n -> (String) n.get("createdAt"),
            Comparator.reverseOrder()
        ));

        return items.stream().limit(30).toList();
    }

    private UUID requireAdvertiserId(JwtClaims claims) {
        if (claims == null || claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
