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

@Controller
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
        System.out.println("Received message: " + message);
        // Find chat by sender and receiver
        var sender = userService.getByUsername(message.getSender());
        var receiver = userService.getByUsername(message.getReceiver());
        
        var chatOpt = chatRepository.findByUser1AndUser2(sender, receiver);
        if (chatOpt.isEmpty()) {
            chatOpt = chatRepository.findByUser2AndUser1(sender, receiver);
        }
        
        if (chatOpt.isPresent()) {
            var chat = chatOpt.get();
            chatService.sendMessage(chat.getId(), message);
        } else {
            System.err.println("Chat not found for users: " + message.getSender() + " and " + message.getReceiver());
        }
    }

    @MessageMapping("/sendKeyPart")
    public void processKeyPart(ChatKeyPart keyPart) {
        System.out.println("Received key part: " + keyPart.getKeypart());
        // Find chat by sender and receiver
        var sender = userService.getByUsername(keyPart.getSender());
        var receiver = userService.getByUsername(keyPart.getReceiver());
        
        var chatOpt = chatRepository.findByUser1AndUser2(sender, receiver);
        if (chatOpt.isEmpty()) {
            chatOpt = chatRepository.findByUser2AndUser1(sender, receiver);
        }
        
        if (chatOpt.isPresent()) {
            var chat = chatOpt.get();
            chatService.sendKeyPart(chat.getId(), keyPart);
        } else {
            System.err.println("Chat not found for users: " + keyPart.getSender() + " and " + keyPart.getReceiver());
        }
    }
}

