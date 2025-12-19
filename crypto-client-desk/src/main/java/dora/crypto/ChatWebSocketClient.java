package dora.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dora.crypto.db.MessageDatabase;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import lombok.Setter;
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

    public byte[] getKey() {
        return key;
    }

    public Chat getChat() {
        return chat;
    }

    private final Chat chat;
    private final String url;
    private StompSessionHandler sessionHandler;
    private String username;
    private BigInteger a;
    @Setter
    private Consumer<ChatMessage> onMessageReceived;
    @Setter
    private Runnable onSubscriptionReady;
    private boolean subscriptionReady = false;
    private boolean keyPartSentInThisSession = false;
    private MessageDatabase messageDatabase;
    private boolean keyExchangePerformed = false;

    public ChatWebSocketClient(String url, Chat chat, String username) {
        this.url = url + "/chat";
        this.chat = chat;
        this.username = username;
        this.objectMapper = new ObjectMapper();
        this.messageDatabase = new MessageDatabase();

        byte[] existingKey = messageDatabase.loadChatKey(chat.getId());
        if (existingKey != null) {
            this.key = existingKey;
            this.keyExchangePerformed = true;
            System.out.println("[" + username + "] Loaded existing encryption key from database for chat " + chat.getId());
        } else {
            this.key = null;
            this.keyExchangePerformed = false;
            this.a = generatePrivateExponent(chat.getAlgorithm(), chat.getP());
            this.constant = chat.getG().modPow(a, chat.getP());
            System.out.println("[" + username + "] No existing key found - will perform key exchange for chat " + chat.getId());
        }
    }

    private BigInteger generatePrivateExponent(String algorithm, BigInteger p) {
        int keyLengthBits = getRequiredKeyLengthBits(algorithm);
        int exponentBits = Math.max(128, keyLengthBits / 2);

        SecureRandom random = new SecureRandom();
        BigInteger pMinusOne = p.subtract(BigInteger.ONE);
        BigInteger a = new BigInteger(exponentBits, random);

        if (a.compareTo(BigInteger.ONE) < 0) {
            a = BigInteger.ONE;
        }

        if (a.compareTo(pMinusOne) >= 0) {
            a = a.mod(pMinusOne);
            if (a.compareTo(BigInteger.ONE) < 0) {
                a = BigInteger.ONE;
            }
        }

        return a;
    }

    private int getRequiredKeyLengthBits(String algorithm) {
        return switch (algorithm.toUpperCase()) {
            case "MARS" -> 256;
            case "RC5" -> 256;
            default -> 256;
        };
    }

    private int getRequiredKeyLengthBytes(String algorithm) {
        return getRequiredKeyLengthBits(algorithm) / 8;
    }

    private void regenerateKeyExchangeParameters() {
        key = null;
        this.a = generatePrivateExponent(chat.getAlgorithm(), chat.getP());
        this.constant = chat.getG().modPow(a, chat.getP());
        System.out.println("[" + username + "] Regenerated key exchange parameters - new private exponent 'a' and constant (g^a mod p): " + constant);
    }

    private void calculateAndStoreSharedKey(BigInteger sharedKeyBigInt) {
        int requiredKeyBytes = getRequiredKeyLengthBytes(chat.getAlgorithm());
        byte[] fullKeyBytes = sharedKeyBigInt.toByteArray();

        if (fullKeyBytes[0] == 0 && fullKeyBytes.length > requiredKeyBytes) {
            byte[] temp = new byte[fullKeyBytes.length - 1];
            System.arraycopy(fullKeyBytes, 1, temp, 0, temp.length);
            fullKeyBytes = temp;
        }

        if (fullKeyBytes.length >= requiredKeyBytes) {
            key = new byte[requiredKeyBytes];
            System.arraycopy(fullKeyBytes, fullKeyBytes.length - requiredKeyBytes, key, 0, requiredKeyBytes);
        } else {
            key = new byte[requiredKeyBytes];
            System.arraycopy(fullKeyBytes, 0, key, requiredKeyBytes - fullKeyBytes.length, fullKeyBytes.length);
        }

        System.out.println("[" + username + "] Calculated shared key (length: " + key.length + " bytes)");
        System.out.println("[" + username + "] Key calculation: (received g^b)^a = g^(a*b) mod p, where a=" + a);
        if (key.length >= 4) {
            System.out.println("[" + username + "] First 4 bytes of calculated key: [" +
                    (key[0] & 0xff) + ", " + (key[1] & 0xff) + ", " + (key[2] & 0xff) + ", " + (key[3] & 0xff) + "]");
        }

        messageDatabase.saveChatKey(chat.getId(), key);
        this.keyExchangePerformed = true;
        System.out.println("[" + username + "] Saved encryption key to database for chat " + chat.getId());
        System.out.println("[" + username + "] Key exchange complete, attempting to send success message...");
        sendSystemMessage("Key exchange successful! You can now send encrypted messages.");
    }

    private void sendSystemMessage(String messageText) {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            if (handler.session != null && handler.session.isConnected() && subscriptionReady) {
                try {
                    var messageToContact = ChatMessage.builder()
                            .sender("System")
                            .receiver(chat.getContactUsername())
                            .message(messageText)
                            .type("SYSTEM")
                            .build();
                    handler.sendChatMessage(messageToContact);
                    System.out.println("[" + username + "] Sent system message to contact: " + chat.getContactUsername());
                    System.out.println("[" + username + "] Successfully sent system message: " + messageText);
                } catch (Exception e) {
                    System.err.println("[" + username + "] Failed to send system message: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.err.println("[" + username + "] Cannot send system message - session not ready. " +
                        "Connected: " + (handler.session != null && handler.session.isConnected()) +
                        ", SubscriptionReady: " + subscriptionReady);
                if (handler.session != null && handler.session.isConnected() && !subscriptionReady) {
                    System.out.println("[" + username + "] Retrying system message send after delay...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                            if (subscriptionReady) {
                                sendSystemMessage(messageText);
                            } else {
                                System.err.println("[" + username + "] Subscription still not ready after delay, message not sent");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            }
        } else {
            System.err.println("[" + username + "] Cannot send system message - session handler not initialized or wrong type");
        }
    }

    public boolean isSubscriptionReady() {
        return subscriptionReady;
    }

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

    public void disconnect() {
        if (sessionHandler != null && sessionHandler instanceof ChatStompSessionHandler) {
            ChatStompSessionHandler handler = (ChatStompSessionHandler) sessionHandler;
            if (handler.session != null && handler.session.isConnected()) {
                try {
                    System.out.println("[" + username + "] Disconnecting WebSocket session...");
                    handler.session.disconnect();
                    System.out.println("[" + username + "] WebSocket session disconnected");
                } catch (Exception e) {
                    System.err.println("[" + username + "] Error disconnecting WebSocket: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        this.onMessageReceived = null;
        this.onSubscriptionReady = null;
        this.subscriptionReady = false;
        this.keyPartSentInThisSession = false;
        this.sessionHandler = null;
        
        System.out.println("[" + username + "] WebSocket client destroyed (key preserved for reconnection)");
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

    class ChatStompSessionHandler extends StompSessionHandlerAdapter {

        StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            System.out.println("Connected to WebSocket server");
            this.session = session;

            if (ChatWebSocketClient.this.key == null) {
                byte[] existingKey = messageDatabase.loadChatKey(chat.getId());
                if (existingKey != null) {
                    ChatWebSocketClient.this.key = existingKey;
                    ChatWebSocketClient.this.keyExchangePerformed = true;
                    System.out.println("[" + username + "] Loaded encryption key from database on reconnection for chat " + chat.getId());
                } else {
                    System.out.println("[" + username + "] No key found in database - will perform key exchange if needed");
                }
            } else {
                System.out.println("[" + username + "] Using existing key from memory on reconnection");
            }
            
            ChatWebSocketClient.this.keyPartSentInThisSession = false;

            String topic = "/topic/messages/" + chat.getId() + "/" + username;
            System.out.println("Subscribing to topic: " + topic);
            var handler = new ChatMessageHandler();
            handler.parentHandler = this;
            session.subscribe(topic, handler);

            new Thread(() -> {
                try {
                    Thread.sleep(100);
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
                keyPartSentInThisSession = true;
            } else {
                System.err.println("[" + username + "] Cannot send key part - session not connected");
            }
        }
    }

    class ChatMessageHandler implements StompFrameHandler {
        public ChatStompSessionHandler parentHandler;

        @Override
        public Type getPayloadType(StompHeaders headers) {
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

            if (chatKeyPart != null) {
                System.out.println("[" + username + "] Received ChatKeyPart - Receiver: " + chatKeyPart.getReceiver() +
                        ", Sender: " + chatKeyPart.getSender() +
                        ", KeyPart: " + chatKeyPart.getKeypart());

                if (Objects.equals(chatKeyPart.getSender(), chat.getContactUsername())) {
                    if (!keyPartSentInThisSession) {
                        System.out.println("[" + username + "] Received key part before sending ours - storing it and waiting for 'ready for key exchange'");
                        return;
                    }

                    BigInteger sharedKeyBigInt = chatKeyPart.getKeypart().modPow(a, chat.getP());
                    calculateAndStoreSharedKey(sharedKeyBigInt);
                } else {
                    System.out.println("[" + username + "] Ignoring key part from unexpected sender: " + chatKeyPart.getSender() +
                            " (expected: " + chat.getContactUsername() + ")");
                }
                return;
            }

            if (chatMessage != null) {
                System.out.println("[" + username + "] Received ChatMessage - Receiver: " + chatMessage.getReceiver() +
                        ", Sender: " + chatMessage.getSender() +
                        ", Type: " + chatMessage.getType() +
                        ", Message: " + chatMessage.getMessage());

                if ("SYSTEM".equals(chatMessage.getType()) || "System".equals(chatMessage.getSender())) {
                    System.out.println("[" + username + "] Received system message: " + chatMessage.getMessage());
                    if (onMessageReceived != null) {
                        onMessageReceived.accept(chatMessage);
                    }
                    return;
                }

                if (Objects.equals(chatMessage.getSender(), chat.getContactUsername())) {
                    if (Objects.equals(chatMessage.getMessage(), "ready for key exchange")) {
                        if (keyExchangePerformed && key != null) {
                            System.out.println("[" + username + "] Received 'ready for key exchange', but key already exists. Skipping key exchange.");
                            sendSystemMessage("Key exchange skipped - using existing key.");
                        } else {
                            System.out.println("[" + username + "] Received 'ready for key exchange', generating new key part...");
                            regenerateKeyExchangeParameters();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            parentHandler.sendKeyPart();
                        }
                    } else {
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


