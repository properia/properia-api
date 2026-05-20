package pt.properia.api.modules.chat.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.chat.domain.ChatConversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationJpaRepository extends JpaRepository<ChatConversation, UUID> {

    List<ChatConversation> findByAdvertiserIdOrderByLastMessageAtDesc(UUID advertiserId);

    List<ChatConversation> findByBuyerUserIdOrderByLastMessageAtDesc(UUID buyerUserId);

    Optional<ChatConversation> findByIdAndAdvertiserId(UUID id, UUID advertiserId);

    Optional<ChatConversation> findByAdvertiserIdAndListingIdAndBuyerUserId(
        UUID advertiserId, UUID listingId, UUID buyerUserId);
}
