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

    private BigInteger constant;
    private byte[] key;
    private final ObjectMapper objectMapper;
    private ChatKeyPart pendingKeyPart = null; // Store key part received before we sent ours

    public byte[] getKey() {
        return key;
    }
    private final Chat chat;
    private final String url;
    private StompSessionHandler sessionHandler;
    private String username;
    private BigInteger a; // Not final - will be regenerated on each key exchange
    private Consumer<ChatMessage> onMessageReceived;
    private Runnable onSubscriptionReady;
    private boolean subscriptionReady = false;
    private boolean keyPartSentInThisSession = false; // Track if we've sent key part in current session

    public ChatWebSocketClient(String url, Chat chat, String username) {
        this.url = url + "/chat";
        this.chat = chat;
        this.username = username;
        this.objectMapper = new ObjectMapper();
        
        // Reset key to null to ensure fresh key exchange on each connection
        this.key = null;
        
        // Generate new private exponent 'a' for each connection
        // This ensures a fresh key exchange every time users connect
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

    /**
     * Regenerates the private exponent 'a' and recalculates constant = g^a mod p.
     * This is called when "ready for key exchange" is received to ensure
     * both users use fresh exponents for the new key exchange.
     */
    private void regenerateKeyExchangeParameters() {
        // Reset key to null to ensure fresh key calculation
        key = null;
        
        // Generate new private exponent 'a' for this key exchange
        // This ensures both users use fresh exponents for the new key exchange session
        this.a = generatePrivateExponent(chat.getAlgorithm(), chat.getP());
        
        // Recalculate constant = g^a mod p with new 'a'
        this.constant = chat.getG().modPow(a, chat.getP());
        
        System.out.println("[" + username + "] Regenerated key exchange parameters - new private exponent 'a' and constant (g^a mod p): " + constant);
    }

    /**
     * Calculates and stores the shared key from the shared key BigInteger.
     * This extracts the required number of bytes based on the algorithm.
     */
    private void calculateAndStoreSharedKey(BigInteger sharedKeyBigInt) {
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
        
        System.out.println("[" + username + "] Calculated shared key (length: " + key.length + " bytes)");
        System.out.println("[" + username + "] Key calculation: (received g^b)^a = g^(a*b) mod p, where a=" + a);
        // Log first few bytes for debugging (don't log full key for security)
        if (key.length >= 4) {
            System.out.println("[" + username + "] First 4 bytes of calculated key: [" + 
                (key[0] & 0xff) + ", " + (key[1] & 0xff) + ", " + (key[2] & 0xff) + ", " + (key[3] & 0xff) + "]");
        }
    }

    public void setOnMessageReceived(Consumer<ChatMessage> callback) {
        this.onMessageReceived = callback;
    }

    public void setOnSubscriptionReady(Runnable callback) {
        this.onSubscriptionReady = callback;
    }

    public boolean isSubscriptionReady() {
        return subscriptionReady;
    }

    /**
     * Manually trigger key exchange by sending key part.
     * This is useful when connecting to a chat that's already in CONNECTED2 status.
     */
    public void initiateKeyExchange() {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            if (handler.session != null && handler.session.isConnected() && subscriptionReady) {
                System.out.println("[" + username + "] Manually initiating key exchange...");
                handler.sendKeyPart();
            } else {
                System.err.println("[" + username + "] Cannot initiate key exchange - session not ready");
            }
        }
    }

    public void sendMessage(String messageText) {
        sendMessage(messageText, "TEXT");
    }

    public void sendMessage(String messageText, String type) {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            if (handler.session != null && handler.session.isConnected()) {
                var message = ChatMessage.builder()
                        .sender(username)
                        .receiver(chat.getContactUsername())
                        .message(messageText)
                        .type(type)
                        .build();
                handler.sendChatMessage(message);
                System.out.println("[" + username + "] Sending message (type: " + type + "): " + messageText);
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
            
            // Reset key and flags on each new connection
            // We do NOT regenerate parameters here to avoid race conditions.
            // Parameters will be regenerated when "ready for key exchange" is received,
            // ensuring both users regenerate at the same time and use matching parameters.
            ChatWebSocketClient.this.key = null;
            ChatWebSocketClient.this.keyPartSentInThisSession = false; // Reset flag for new connection
            ChatWebSocketClient.this.pendingKeyPart = null; // Clear any pending key part from previous session
            
            System.out.println("[" + username + "] Key reset for new connection - will regenerate parameters when 'ready for key exchange' is received");
            
            // Subscribe to chat-specific topic: /topic/messages/{chatId}/{username}
            String topic = "/topic/messages/" + chat.getId() + "/" + username;
            System.out.println("Subscribing to topic: " + topic);
            var handler = new ChatMessageHandler();
            handler.parentHandler = this;
            session.subscribe(topic, handler);
            
            // Mark subscription as ready after a short delay to ensure it's active
            // STOMP subscriptions are typically ready immediately, but we add a small delay
            // to ensure the subscription is fully processed by the server
            new Thread(() -> {
                try {
                    Thread.sleep(100); // Small delay to ensure subscription is active
                    subscriptionReady = true;
                    System.out.println("[" + username + "] Subscription ready for topic: " + topic);
                    if (onSubscriptionReady != null) {
                        onSubscriptionReady.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
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
            // Ensure we have valid parameters before sending
            if (constant == null) {
                System.err.println("[" + username + "] Cannot send key part - parameters not initialized. Regenerating...");
                regenerateKeyExchangeParameters();
            }
            
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
                keyPartSentInThisSession = true; // Mark that we've sent our key part
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
                
                // Always recalculate key when receiving a key part (handles reconnection)
                // Verify sender is the contact (safety check)
                if (Objects.equals(chatKeyPart.getSender(), chat.getContactUsername())) {
                    // If we haven't sent our key part yet, store this key part and wait for "ready for key exchange".
                    // We'll calculate the shared key after we send our key part to ensure we're using matching parameters.
                    if (!keyPartSentInThisSession) {
                        System.out.println("[" + username + "] Received key part before sending ours - storing it and waiting for 'ready for key exchange'");
                        pendingKeyPart = chatKeyPart;
                        return; // Don't calculate key yet - wait until we've sent our key part
                    }
                    
                    // We've already sent our key part, so we can safely calculate the shared key
                    // Calculate shared key: g^(a*b) mod p
                    // User A (with exponent 'a') calculates: (received g^b)^a = g^(a*b) mod p
                    // User B (with exponent 'b') calculates: (received g^a)^b = g^(a*b) mod p
                    // Both produce the same shared key: g^(a*b) mod p
                    // Note: 'a' here is the current user's private exponent, and the received
                    // keypart is the other user's g^b (or g^a from their perspective)
                    BigInteger sharedKeyBigInt = chatKeyPart.getKeypart().modPow(a, chat.getP());
                    calculateAndStoreSharedKey(sharedKeyBigInt);
                } else {
                    System.out.println("[" + username + "] Ignoring key part from unexpected sender: " + chatKeyPart.getSender() + 
                                     " (expected: " + chat.getContactUsername() + ")");
                }
                return;
            }

            // Handle ChatMessage - no need to check receiver since topic already filters
            if (chatMessage != null) {
                System.out.println("[" + username + "] Received ChatMessage - Receiver: " + chatMessage.getReceiver() + 
                                 ", Sender: " + chatMessage.getSender() + 
                                 ", Message: " + chatMessage.getMessage());
                
                // Handle system messages (like "chat deleted")
                if ("SYSTEM".equals(chatMessage.getSender())) {
                    if (Objects.equals(chatMessage.getMessage(), "chat deleted")) {
                        System.out.println("[" + username + "] Received 'chat deleted' notification");
                        // Notify callback about chat deletion
                        if (onMessageReceived != null) {
                            onMessageReceived.accept(chatMessage);
                        }
                    }
                    return;
                }
                
                // Verify sender is the contact (safety check)
                if (Objects.equals(chatMessage.getSender(), chat.getContactUsername())) {
                    if (Objects.equals(chatMessage.getMessage(), "ready for key exchange")) {
                        System.out.println("[" + username + "] Received 'ready for key exchange', generating new key part...");
                        
                        // Discard any pending key part from previous exchange - it's invalid now
                        // because we're starting a new key exchange with fresh parameters
                        if (pendingKeyPart != null) {
                            System.out.println("[" + username + "] Discarding pending key part from previous exchange");
                            pendingKeyPart = null;
                        }
                        
                        // Generate new private exponent and key part for this key exchange
                        // This ensures both users use fresh exponents and calculate the same new key
                        // Both the already-connected user and newly-connecting user will regenerate
                        // to ensure they're using the same key exchange session
                        regenerateKeyExchangeParameters();
                        // Small delay to ensure both users have regenerated before sending
                        // This helps with synchronization
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
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


