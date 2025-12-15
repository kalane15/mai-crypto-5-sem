package dora.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ChatWebSocketClient {

    private final BigInteger constant;
    private byte[] key;
    private final ObjectMapper objectMapper;

    public byte[] getKey() {
        return key;
    }
    private final Chat chat;
    private final String url;
    private StompSessionHandler sessionHandler;
    private String username;
    private final BigInteger a;
    private Consumer<ChatMessage> onMessageReceived;

    public ChatWebSocketClient(String url, Chat chat, String username) {
        this.url = url + "/chat";
        this.chat = chat;
        this.username = username;
        this.objectMapper = new ObjectMapper();
        
        // Choose 'a' based on the required key length for the algorithm
        this.a = generatePrivateExponent(chat.getAlgorithm(), chat.getP());
        this.constant = chat.getG().modPow(a, chat.getP());
    }

    /**
     * Generates a private exponent 'a' for Diffie-Hellman key exchange.
     * Since the final key is g^(a*b) mod p, we don't need 'a' to be as large
     * as the final key length. We use a smaller exponent for efficiency while
     * maintaining security.
     * 
     * @param algorithm The encryption algorithm (MARS, RC5)
     * @param p The prime modulus
     * @return A random BigInteger 'a' suitable for the key length
     */
    private BigInteger generatePrivateExponent(String algorithm, BigInteger p) {
        // Determine required key length in bits based on algorithm
        int keyLengthBits = getRequiredKeyLengthBits(algorithm);
        
        // For the exponent 'a', we use a smaller size than the final key length
        // This is because g^(a*b) mod p will be large enough even with smaller exponents
        // Using keyLengthBits/2 provides good security while keeping exponents reasonable
        int exponentBits = Math.max(128, keyLengthBits / 2); // At least 128 bits for security
        
        SecureRandom random = new SecureRandom();
        BigInteger pMinusOne = p.subtract(BigInteger.ONE);
        
        // Generate a random number 'a' with exponentBits bits
        BigInteger a = new BigInteger(exponentBits, random);
        
        // Ensure a is at least 1 (should always be true for positive bit length)
        if (a.compareTo(BigInteger.ONE) < 0) {
            a = BigInteger.ONE;
        }
        
        // If by chance a >= p-1 (very unlikely given p is huge), reduce it
        if (a.compareTo(pMinusOne) >= 0) {
            a = a.mod(pMinusOne);
            if (a.compareTo(BigInteger.ONE) < 0) {
                a = BigInteger.ONE;
            }
        }
        
        return a;
    }

    /**
     * Returns the required key length in bits for the given algorithm.
     */
    private int getRequiredKeyLengthBits(String algorithm) {
        return switch (algorithm.toUpperCase()) {
            case "MARS" -> 256; // MARS supports 128-448 bit keys, use 256 for security
            case "RC5" -> 256;  // RC5 supports variable keys, use 256 for security
            default -> 256; // Default to 256 bits for unknown algorithms
        };
    }

    /**
     * Returns the required key length in bytes for the given algorithm.
     */
    private int getRequiredKeyLengthBytes(String algorithm) {
        return getRequiredKeyLengthBits(algorithm) / 8;
    }

    public void setOnMessageReceived(Consumer<ChatMessage> callback) {
        this.onMessageReceived = callback;
    }

    public void sendMessage(String messageText) {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            if (handler.session != null && handler.session.isConnected()) {
                var message = ChatMessage.builder()
                        .sender(username)
                        .receiver(chat.getContactUsername())
                        .message(messageText)
                        .build();
                handler.sendChatMessage(message);
                System.out.println("[" + username + "] Sending message: " + messageText);
            } else {
                System.err.println("[" + username + "] Cannot send message - session not connected");
            }
        } else {
            System.err.println("[" + username + "] Cannot send message - handler not initialized");
        }
    }

    public boolean isConnected() {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            return handler.session != null && handler.session.isConnected();
        }
        return false;
    }

    public CompletableFuture<Void> start() {
        var client = new StandardWebSocketClient();

        WebSocketStompClient stompClient = new WebSocketStompClient(client);
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());

        this.sessionHandler = new ChatStompSessionHandler();
        System.out.println("Connecting to WebSocket: " + this.url);
        return stompClient.connectAsync(this.url, sessionHandler)
                .thenAccept(session -> {
                    System.out.println("WebSocket connection established successfully");
                })
                .exceptionally(ex -> {
                    System.err.println("Failed to connect to WebSocket: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
    }

    // Статический обработчик сессий
    class ChatStompSessionHandler extends StompSessionHandlerAdapter {

        StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            System.out.println("Connected to WebSocket server");
            this.session = session;
            // Subscribe to chat-specific topic: /topic/messages/{chatId}/{username}
            String topic = "/topic/messages/" + chat.getId() + "/" + username;
            System.out.println("Subscribing to topic: " + topic);
            var handler = new ChatMessageHandler();
            handler.parentHandler = this;
            session.subscribe(topic, handler);
        }

        @Override
        public void handleException(StompSession session,
                                    StompCommand command,
                                    StompHeaders headers,
                                    byte[] payload,
                                    Throwable exception) {
            exception.printStackTrace();
        }

        public void sendChatMessage(ChatMessage message) {
            session.send("/app/sendMessage", message);
        }

        public void sendKeyPart() {
            var keypart = ChatKeyPart.builder()
                    .sender(username)
                    .receiver(chat.getContactUsername())
                    .keypart(constant)
                    .build();

            System.out.println("[" + username + "] Sending key part - Sender: " + username + 
                             ", Receiver: " + chat.getContactUsername() + 
                             ", KeyPart (g^a mod p): " + constant);
            if (session != null && session.isConnected()) {
                session.send("/app/sendKeyPart", keypart);
            } else {
                System.err.println("[" + username + "] Cannot send key part - session not connected");
            }
        }
    }

    // Обработчик для получения сообщений
    class ChatMessageHandler implements StompFrameHandler {
        public ChatStompSessionHandler parentHandler;

        @Override
        public Type getPayloadType(StompHeaders headers) {
            // Check content-type header to determine message type
            String contentType = headers.getFirst("content-type");
            if (contentType != null && contentType.contains("application/json")) {
                // Try to determine type from headers or default to Object
                // We'll handle deserialization in handleFrame
                return Object.class;
            }
            return Object.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            System.out.println("Received frame on chat-specific topic");
            System.out.println("Payload type: " + (payload != null ? payload.getClass().getName() : "null"));
            System.out.println("Payload: " + payload);
            
            if (payload == null) {
                System.out.println("Received null payload");
                return;
            }

            // Try to deserialize as ChatMessage first
            ChatMessage chatMessage = null;
            ChatKeyPart chatKeyPart = null;

            if (payload instanceof ChatMessage) {
                chatMessage = (ChatMessage) payload;
            } else if (payload instanceof ChatKeyPart) {
                chatKeyPart = (ChatKeyPart) payload;
            } else {
                try {
                    if (payload instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> map = (java.util.Map<String, Object>) payload;

                        if (map.containsKey("keypart")) {
                            chatKeyPart = objectMapper.convertValue(map, ChatKeyPart.class);
                        } else if (map.containsKey("message") || map.containsKey("sender") || map.containsKey("receiver")) {
                            chatMessage = objectMapper.convertValue(map, ChatMessage.class);
                        }
                    } else if (payload instanceof String) {
                        String json = (String) payload;
                        if (json.contains("keypart")) {
                            chatKeyPart = objectMapper.readValue(json, ChatKeyPart.class);
                        } else {
                            chatMessage = objectMapper.readValue(json, ChatMessage.class);
                        }
                    } else if (payload instanceof byte[]) {
                        String json = new String((byte[]) payload);
                        if (json.contains("keypart")) {
                            chatKeyPart = objectMapper.readValue(json, ChatKeyPart.class);
                        } else {
                            chatMessage = objectMapper.readValue(json, ChatMessage.class);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to deserialize payload: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Handle ChatKeyPart - no need to check receiver/sender since topic already filters
            if (chatKeyPart != null) {
                System.out.println("[" + username + "] Received ChatKeyPart - Receiver: " + chatKeyPart.getReceiver() + 
                                 ", Sender: " + chatKeyPart.getSender() + 
                                 ", KeyPart: " + chatKeyPart.getKeypart());
                if (key == null) {
                    // Verify sender is the contact (safety check)
                    if (Objects.equals(chatKeyPart.getSender(), chat.getContactUsername())) {
                        // Calculate shared key: g^(a*b) mod p
                        BigInteger sharedKeyBigInt = chatKeyPart.getKeypart().modPow(a, chat.getP());
                        
                        // Extract only the required number of bytes from the shared key
                        int requiredKeyBytes = getRequiredKeyLengthBytes(chat.getAlgorithm());
                        byte[] fullKeyBytes = sharedKeyBigInt.toByteArray();
                        
                        // Handle sign bit (BigInteger.toByteArray() may include sign bit)
                        if (fullKeyBytes[0] == 0 && fullKeyBytes.length > requiredKeyBytes) {
                            // Remove leading zero byte
                            byte[] temp = new byte[fullKeyBytes.length - 1];
                            System.arraycopy(fullKeyBytes, 1, temp, 0, temp.length);
                            fullKeyBytes = temp;
                        }
                        
                        // Extract the required number of bytes
                        if (fullKeyBytes.length >= requiredKeyBytes) {
                            // Take the last requiredKeyBytes bytes (most significant)
                            key = new byte[requiredKeyBytes];
                            System.arraycopy(fullKeyBytes, fullKeyBytes.length - requiredKeyBytes, key, 0, requiredKeyBytes);
                        } else {
                            // If key is shorter than required, pad with zeros at the beginning
                            key = new byte[requiredKeyBytes];
                            System.arraycopy(fullKeyBytes, 0, key, requiredKeyBytes - fullKeyBytes.length, fullKeyBytes.length);
                        }
                        
                        System.out.println("[" + username + "] Calculated shared key (length: " + key.length + " bytes): " + java.util.Arrays.toString(key));
                    } else {
                        System.out.println("[" + username + "] Ignoring key part from unexpected sender: " + chatKeyPart.getSender() + 
                                         " (expected: " + chat.getContactUsername() + ")");
                    }
                } else {
                    System.out.println("[" + username + "] Key already calculated, ignoring duplicate key part");
                }
                return;
            }

            // Handle ChatMessage - no need to check receiver since topic already filters
            if (chatMessage != null) {
                System.out.println("[" + username + "] Received ChatMessage - Receiver: " + chatMessage.getReceiver() + 
                                 ", Sender: " + chatMessage.getSender() + 
                                 ", Message: " + chatMessage.getMessage());
                // Verify sender is the contact (safety check)
                if (Objects.equals(chatMessage.getSender(), chat.getContactUsername())) {
                    if (Objects.equals(chatMessage.getMessage(), "ready for key exchange")) {
                        System.out.println("[" + username + "] Received 'ready for key exchange', sending key part...");
                        parentHandler.sendKeyPart();
                    } else {
                        // Regular chat message - notify callback
                        if (onMessageReceived != null) {
                            onMessageReceived.accept(chatMessage);
                        }
                    }
                } else {
                    System.out.println("[" + username + "] Ignoring message from unexpected sender: " + chatMessage.getSender() + 
                                     " (expected: " + chat.getContactUsername() + ")");
                }
                return;
            }

            System.out.println("Could not deserialize payload as ChatMessage or ChatKeyPart");
        }
    }
}


