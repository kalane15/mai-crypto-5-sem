package dora.server.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous Kafka producer for publishing chat messages
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaMessageProducer {

    private static final String CHAT_MESSAGES_TOPIC = "chat-messages";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Asynchronously publish a chat message to Kafka
     */
    @Async("kafkaTaskExecutor")
    public CompletableFuture<Void> publishMessage(Long chatId, KafkaMessageWrapper messageWrapper) {
        try {
            String key = chatId + "-" + messageWrapper.getReceiverUsername();
            log.info("Publishing message to Kafka topic: {}, key: {}, type: {}", 
                    CHAT_MESSAGES_TOPIC, key, messageWrapper.getType());

            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                    CHAT_MESSAGES_TOPIC, 
                    key, 
                    messageWrapper
            );

            return future.thenRun(() -> {
                log.info("Message published successfully to Kafka topic: {}", CHAT_MESSAGES_TOPIC);
            }).exceptionally(ex -> {
                log.error("Failed to publish message to Kafka", ex);
                throw new RuntimeException("Failed to publish message to Kafka", ex);
            });
        } catch (Exception e) {
            log.error("Error publishing message to Kafka", e);
            return CompletableFuture.failedFuture(e);
        }
    }
}

