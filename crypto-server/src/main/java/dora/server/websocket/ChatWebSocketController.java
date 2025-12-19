package dora.server.websocket;


import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import dora.server.auth.UserService;
import dora.server.chat.ChatRepository;
import dora.server.chat.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
public class ChatWebSocketController {
    @Autowired
    private ChatService chatService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private ChatRepository chatRepository;

    @MessageMapping("/sendMessage")
    public void processMessage(ChatMessage message) {
        log.info("Received message via WebSocket: {}", message);
        
        if ("SYSTEM".equals(message.getType()) || "System".equals(message.getSender())) {
            try {
                var receiver = userService.getByUsername(message.getReceiver());
                var chats = chatRepository.findByUser1OrUser2(receiver, receiver);
                
                var chatOpt = chats.stream()
                        .filter(chat -> {
                            boolean receiverIsUser1 = chat.getUser1().getUsername().equals(receiver.getUsername());
                            boolean receiverIsUser2 = chat.getUser2().getUsername().equals(receiver.getUsername());
                            return (receiverIsUser1 && chat.isConnectedUser1()) || 
                                   (receiverIsUser2 && chat.isConnectedUser2()) ||
                                   chat.isConnectedUser1() || chat.isConnectedUser2();
                        })
                        .findFirst();
                
                if (chatOpt.isPresent()) {
                    var chat = chatOpt.get();
                    log.info("Found chat {} for system message to receiver {}", chat.getId(), message.getReceiver());
                    chatService.sendMessage(chat.getId(), message);
                } else {
                    log.warn("No active chat found for system message receiver: {}", message.getReceiver());
                }
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                log.error("Receiver not found for system message: {}", message.getReceiver(), e);
            }
            return;
        }
        
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
            log.error("Chat not found for users: {} and {}", message.getSender(), message.getReceiver());
        }
    }

    @MessageMapping("/sendKeyPart")
    public void processKeyPart(ChatKeyPart keyPart) {
        log.info("Received key part via WebSocket: sender={}, receiver={}", keyPart.getSender(), keyPart.getReceiver());
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
            log.error("Chat not found for users: {} and {}", keyPart.getSender(), keyPart.getReceiver());
        }
    }
}

