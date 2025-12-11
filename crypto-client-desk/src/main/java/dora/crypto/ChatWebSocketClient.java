package dora.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.Scanner;

public class ChatWebSocketClient {

    private final BigInteger constant;
    private byte[] key;
    private final ObjectMapper objectMapper;
    private final Chat chat;
    private final String url;

    public ChatWebSocketClient(String url, Chat chat) {
        this.url = url + "/chat";
        BigInteger a = BigInteger.valueOf(2);
        this.constant = chat.getG().modPow(a, chat.getP());
        this.objectMapper = new ObjectMapper();
        this.chat = chat;
    }

    public void start() {
        WebSocketClient client = new StandardWebSocketClient();

        WebSocketStompClient stompClient = new WebSocketStompClient(client);
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());

        StompSessionHandler sessionHandler = new ChatStompSessionHandler();
        stompClient.connectAsync(this.url, sessionHandler)
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                });
    }

    // Статический обработчик сессий
    class ChatStompSessionHandler extends StompSessionHandlerAdapter {

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            System.out.println("Connected to WebSocket server");

            // Подписываемся на тему
            session.subscribe("/topic/messages", new ChatMessageHandler());

            // Отправляем данные после установления соединения
            sendMessage(session);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
            exception.printStackTrace();
        }

        private void sendMessage(StompSession session) {
            // Пример отправки сообщения на сервер через STOMP
//            ChatMessage message = new ChatMessage("Hello from Java WebSocket client!");
            session.send("/app/sendMessage", "Hello world");
        }
    }

    // Обработчик для получения сообщений
    class ChatMessageHandler implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return ChatMessage.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String message = (String) payload;
            System.out.println("Received message: " + message);

            // Обработка ключа
            if (key == null) {
                try {
                    ChatKeyPart receivedMessage = objectMapper.readValue(message, ChatKeyPart.class);
                    key = constant.modPow(receivedMessage.getKeypart(), chat.getP()).toByteArray();
                    System.out.println("Successfully created key: " + Arrays.toString(key));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                // Обработка сообщений после того, как ключ уже был получен
                System.out.println("Processed message after key exchange: " + message);
            }
        }
    }
}
