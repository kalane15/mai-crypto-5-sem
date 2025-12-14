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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class ChatWebSocketClient {

    private final BigInteger constant;
    private byte[] key;
    private final ObjectMapper objectMapper;
    private final Chat chat;
    private final String url;
    private StompSessionHandler sessionHandler;
    private String username;
    private BigInteger a = BigInteger.valueOf(2);

    public ChatWebSocketClient(String url, Chat chat, String username) {
        this.url = url + "/chat";
        this.constant = chat.getG().modPow(a, chat.getP());
        this.objectMapper = new ObjectMapper();
        this.chat = chat;
        this.username = username;
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

        private StompSession session;

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
                        key = chatKeyPart.getKeypart().modPow(a, chat.getP()).toByteArray();
                        System.out.println("[" + username + "] Calculated shared key: " + java.util.Arrays.toString(key));
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


