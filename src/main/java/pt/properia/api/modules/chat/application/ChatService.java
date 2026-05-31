package pt.properia.api.modules.chat.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.chat.application.dto.ConversationDto;
import pt.properia.api.modules.chat.application.dto.MessageDto;
import pt.properia.api.modules.chat.domain.ChatConversation;
import pt.properia.api.modules.chat.domain.ChatMessage;
import pt.properia.api.modules.chat.infrastructure.ChatConversationJpaRepository;
import pt.properia.api.modules.chat.infrastructure.ChatMessageJpaRepository;
import pt.properia.api.modules.listings.infrastructure.ListingJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ChatService {

    private final ChatConversationJpaRepository conversationRepo;
    private final ChatMessageJpaRepository messageRepo;
    private final ListingJpaRepository listingRepo;
    private final ChatEventPublisher eventPublisher;

    public ChatService(
            ChatConversationJpaRepository conversationRepo,
            ChatMessageJpaRepository messageRepo,
            ListingJpaRepository listingRepo,
            ChatEventPublisher eventPublisher) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.listingRepo = listingRepo;
        this.eventPublisher = eventPublisher;
    }

    // ── Advertiser side ────────────────────────────────────────────────────────

    public List<ConversationDto> listForAdvertiser(UUID advertiserId) {
        return conversationRepo.findByAdvertiserIdOrderByLastMessageAtDesc(advertiserId)
            .stream().map(c -> toDto(c, List.of())).toList();
    }

    public ConversationDto getConversation(UUID conversationId, UUID advertiserId) {
        var conv = conversationRepo.findByIdAndAdvertiserId(conversationId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Conversa não encontrada."));
        var messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conversationId)
            .stream().map(this::toMessageDto).toList();
        return toDto(conv, messages);
    }

    public MessageDto sendAdvertiserMessage(UUID conversationId, UUID advertiserId, UUID senderUserId, String body) {
        var conv = conversationRepo.findByIdAndAdvertiserId(conversationId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Conversa não encontrada."));

        if ("closed".equals(conv.getStatus())) {
            throw new DomainException("CONFLICT", "Esta conversa está fechada.");
        }

        var message = buildMessage(conv, "advertiser_member", senderUserId, body);
        var saved = messageRepo.save(message);

        updateConversationPreview(conv, body);
        var dto = toMessageDto(saved);
        eventPublisher.publishNewMessage(conv.getId(), conv.getAdvertiserId(), conv.getBuyerUserId(), dto);
        return dto;
    }

    public void closeConversation(UUID conversationId, UUID advertiserId) {
        var conv = conversationRepo.findByIdAndAdvertiserId(conversationId, advertiserId)
            .orElseThrow(() -> DomainException.notFound("Conversa não encontrada."));
        conv.setStatus("closed");
        conv.setClosedAt(Instant.now());
        conversationRepo.save(conv);
    }

    // ── Buyer side ─────────────────────────────────────────────────────────────

    public List<ConversationDto> listForBuyer(UUID buyerUserId) {
        return conversationRepo.findByBuyerUserIdOrderByLastMessageAtDesc(buyerUserId)
            .stream().map(c -> toDto(c, List.of())).toList();
    }

    public ConversationDto getOrCreateConversation(UUID listingId, UUID buyerUserId, String initialMessage) {
        var listing = listingRepo.findById(listingId)
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        var existing = conversationRepo.findByAdvertiserIdAndListingIdAndBuyerUserId(
            listing.getAdvertiserId(), listingId, buyerUserId);

        if (existing.isPresent()) {
            var conv = existing.get();
            var messages = messageRepo.findByConversationIdOrderByCreatedAtAsc(conv.getId())
                .stream().map(this::toMessageDto).toList();
            return toDto(conv, messages);
        }

        var conv = new ChatConversation();
        conv.setAdvertiserId(listing.getAdvertiserId());
        conv.setListingId(listingId);
        conv.setBuyerUserId(buyerUserId);
        conv.setLastMessageAt(Instant.now());
        conv.setLastMessagePreview(truncate(initialMessage));
        var savedConv = conversationRepo.save(conv);

        MessageDto firstMessage = null;
        if (initialMessage != null && !initialMessage.isBlank()) {
            var msg = buildMessage(savedConv, "buyer", buyerUserId, initialMessage);
            firstMessage = toMessageDto(messageRepo.save(msg));
            eventPublisher.publishNewMessage(savedConv.getId(), savedConv.getAdvertiserId(), buyerUserId, firstMessage);
        }

        return toDto(savedConv, firstMessage != null ? List.of(firstMessage) : List.of());
    }

    public MessageDto sendBuyerMessage(UUID conversationId, UUID buyerUserId, String body) {
        var conv = conversationRepo.findById(conversationId)
            .filter(c -> buyerUserId.equals(c.getBuyerUserId()))
            .orElseThrow(() -> DomainException.notFound("Conversa não encontrada."));

        if ("closed".equals(conv.getStatus())) {
            throw new DomainException("CONFLICT", "Esta conversa está fechada.");
        }

        var message = buildMessage(conv, "buyer", buyerUserId, body);
        var saved = messageRepo.save(message);
        updateConversationPreview(conv, body);
        var dto = toMessageDto(saved);
        eventPublisher.publishNewMessage(conv.getId(), conv.getAdvertiserId(), conv.getBuyerUserId(), dto);
        return dto;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ChatMessage buildMessage(ChatConversation conv, String senderType, UUID senderUserId, String body) {
        var msg = new ChatMessage();
        msg.setConversationId(conv.getId());
        msg.setAdvertiserId(conv.getAdvertiserId());
        msg.setListingId(conv.getListingId());
        msg.setLeadId(conv.getLeadId());
        msg.setSenderType(senderType);
        msg.setSenderUserId(senderUserId);
        msg.setBody(body.strip());
        return msg;
    }

    private void updateConversationPreview(ChatConversation conv, String body) {
        conv.setLastMessageAt(Instant.now());
        conv.setLastMessagePreview(truncate(body));
        conversationRepo.save(conv);
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }

    private ConversationDto toDto(ChatConversation c, List<MessageDto> messages) {
        return new ConversationDto(
            c.getId(), c.getAdvertiserId(), c.getListingId(), c.getLeadId(), c.getBuyerUserId(),
            c.getStatus(), c.getLastMessageAt(), c.getLastMessagePreview(), c.getCreatedAt(), messages
        );
    }

    private MessageDto toMessageDto(ChatMessage m) {
        return new MessageDto(
            m.getId(), m.getConversationId(), m.getSenderType(), m.getSenderUserId(),
            m.getMessageType(), m.getBody(), m.getCreatedAt()
        );
    }
}
