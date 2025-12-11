package dora.server.websocket;


import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class ChatWebSocketController {

    @MessageMapping("/sendMessage")
    @SendTo("/topic/messages")
    public String processMessage(String message) {
        System.out.println("Received message: " + message);


        return message;
    }

    @MessageMapping("/sendKeyPart")
    @SendTo("/topic/messages")
    public ChatKeyPart processKeyPart(ChatKeyPart keyPart) {
        System.out.println("Received key part: " + keyPart.getKeypart());

        return keyPart;
    }
}

