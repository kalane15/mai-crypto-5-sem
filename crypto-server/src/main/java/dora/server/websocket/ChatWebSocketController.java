package dora.server.websocket;


import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import dora.server.auth.UserService;
import dora.server.chat.ChatRepository;
import dora.server.chat.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
public class ChatWebSocketController {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private ChatService chatService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private ChatRepository chatRepository;

    @MessageMapping("/sendMessage")
    public void processMessage(ChatMessage message) {
        log.info("Received message via WebSocket: {}", message);
        // Find chat by sender and receiver
        var sender = userService.getByUsername(message.getSender());
        var receiver = userService.getByUsername(message.getReceiver());
        
        var chatOpt = chatRepository.findByUser1AndUser2(sender, receiver);
        if (chatOpt.isEmpty()) {
            chatOpt = chatRepository.findByUser2AndUser1(sender, receiver);
        }
        
        if (chatOpt.isPresent()) {
            var chat = chatOpt.get();
            // Send message through Kafka (asynchronous)
            chatService.sendMessage(chat.getId(), message);
        } else {
            log.error("Chat not found for users: {} and {}", message.getSender(), message.getReceiver());
        }
    }

    @MessageMapping("/sendKeyPart")
    public void processKeyPart(ChatKeyPart keyPart) {
        log.info("Received key part via WebSocket: sender={}, receiver={}", keyPart.getSender(), keyPart.getReceiver());
        // Find chat by sender and receiver
        var sender = userService.getByUsername(keyPart.getSender());
        var receiver = userService.getByUsername(keyPart.getReceiver());
        
        var chatOpt = chatRepository.findByUser1AndUser2(sender, receiver);
        if (chatOpt.isEmpty()) {
            chatOpt = chatRepository.findByUser2AndUser1(sender, receiver);
        }
        
        if (chatOpt.isPresent()) {
            var chat = chatOpt.get();
            // Send key part through Kafka (asynchronous)
            chatService.sendKeyPart(chat.getId(), keyPart);
        } else {
            log.error("Chat not found for users: {} and {}", keyPart.getSender(), keyPart.getReceiver());
        }
    }
}

