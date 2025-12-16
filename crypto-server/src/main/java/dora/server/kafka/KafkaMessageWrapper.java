package dora.server.kafka;

import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Wrapper class for Kafka messages to distinguish between ChatMessage and ChatKeyPart
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KafkaMessageWrapper implements Serializable {
    public enum MessageType {
        CHAT_MESSAGE,
        KEY_PART
    }

    private MessageType type;
    private Long chatId;
    private ChatMessage chatMessage;
    private ChatKeyPart chatKeyPart;
    private String receiverUsername;

    public static KafkaMessageWrapper forChatMessage(Long chatId, ChatMessage message) {
        return new KafkaMessageWrapper(MessageType.CHAT_MESSAGE, chatId, message, null, message.getReceiver());
    }

    public static KafkaMessageWrapper forKeyPart(Long chatId, ChatKeyPart keyPart) {
        return new KafkaMessageWrapper(MessageType.KEY_PART, chatId, null, keyPart, keyPart.getReceiver());
    }
}

