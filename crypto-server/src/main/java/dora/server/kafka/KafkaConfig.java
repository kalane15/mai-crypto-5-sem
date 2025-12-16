package dora.server.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:chat-message-consumer-group}")
    private String groupId;

    // Jackson ObjectMapper for JSON serialization/deserialization
    @Bean
    public ObjectMapper kafkaObjectMapper() {
        return new ObjectMapper();
    }

    // Custom JSON Serializer using Jackson
    public static class JsonKafkaSerializer implements Serializer<Object> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ObjectWriter writer = objectMapper.writer();

        @Override
        public byte[] serialize(String topic, Object data) {
            if (data == null) {
                return null;
            }
            try {
                return writer.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new SerializationException("Error serializing JSON message", e);
            }
        }
    }

    // Custom JSON Deserializer using Jackson
    public static class JsonKafkaDeserializer implements Deserializer<Object> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private ObjectReader reader;

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            String defaultType = (String) configs.get("spring.json.value.default.type");
            if (defaultType != null) {
                try {
                    Class<?> clazz = Class.forName(defaultType);
                    reader = objectMapper.readerFor(clazz);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load default type: " + defaultType, e);
                }
            } else {
                reader = objectMapper.reader();
            }
        }

        @Override
        public Object deserialize(String topic, byte[] data) {
            if (data == null) {
                return null;
            }
            try {
                return reader.readValue(data);
            } catch (IOException e) {
                throw new SerializationException("Error deserializing JSON message", e);
            }
        }
    }

    // Producer Configuration
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonKafkaSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all"); // Wait for all replicas
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // Consumer Configuration
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Use ErrorHandlingDeserializer to wrap the key deserializer
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        
        // Use ErrorHandlingDeserializer to wrap the value deserializer (custom JsonKafkaDeserializer)
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonKafkaDeserializer.class);
        
        // Configure default type for deserialization
        props.put("spring.json.value.default.type", "dora.server.kafka.KafkaMessageWrapper");
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // Process messages concurrently

        
        return factory;
    }
}

