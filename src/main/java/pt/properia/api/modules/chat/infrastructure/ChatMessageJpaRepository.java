package pt.properia.api.modules.chat.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import pt.properia.api.modules.chat.domain.ChatMessage;

import java.util.List;
import java.util.UUID;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
