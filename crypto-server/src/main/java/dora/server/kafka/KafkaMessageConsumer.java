package dora.server.kafka;

import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Asynchronous Kafka consumer for routing messages to WebSocket clients
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaMessageConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = "chat-messages", groupId = "chat-message-consumer-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeMessage(KafkaMessageWrapper messageWrapper) {
        try {
            if (messageWrapper == null) {
                log.error("Received null message wrapper from Kafka");
                return;
            }
            
            log.info("Consumed message from Kafka: type={}, chatId={}, receiver={}", 
                    messageWrapper.getType(), 
                    messageWrapper.getChatId(), 
                    messageWrapper.getReceiverUsername());

            String topic = "/topic/messages/" + messageWrapper.getChatId() + "/" + messageWrapper.getReceiverUsername();

            if (messageWrapper.getType() == KafkaMessageWrapper.MessageType.CHAT_MESSAGE) {
                ChatMessage message = messageWrapper.getChatMessage();
                log.info("Routing ChatMessage to WebSocket topic: {}, message: {}", topic, message);
                messagingTemplate.convertAndSend(topic, message);
            } else if (messageWrapper.getType() == KafkaMessageWrapper.MessageType.KEY_PART) {
                ChatKeyPart keyPart = messageWrapper.getChatKeyPart();
                log.info("Routing ChatKeyPart to WebSocket topic: {}, sender: {}, receiver: {}", 
                        topic, keyPart.getSender(), keyPart.getReceiver());
                messagingTemplate.convertAndSend(topic, keyPart);
            } else {
                log.warn("Unknown message type: {}", messageWrapper.getType());
            }
        } catch (Exception e) {
            log.error("Error processing message from Kafka", e);
        }
    }
}

