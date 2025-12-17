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
        
        // Handle system messages specially - they don't have a real sender user
        if ("SYSTEM".equals(message.getType()) || "System".equals(message.getSender())) {
            try {
                // For system messages, find chat by receiver only
                // System messages are sent to both users in a chat, so we need to find the chat
                // by looking at the receiver and finding their active chats
                var receiver = userService.getByUsername(message.getReceiver());
                
                // Find all chats for the receiver
                var chats = chatRepository.findByUser1OrUser2(receiver, receiver);
                
                // Find an active chat (where at least one user is connected)
                // Since system messages are sent in the context of a specific chat,
                // we'll use the first active chat for the receiver
                // Note: This assumes the receiver has only one active chat, or we use the first one
                var chatOpt = chats.stream()
                        .filter(chat -> {
                            // Check if receiver is user1 and user1 is connected, or receiver is user2 and user2 is connected
                            boolean receiverIsUser1 = chat.getUser1().getUsername().equals(receiver.getUsername());
                            boolean receiverIsUser2 = chat.getUser2().getUsername().equals(receiver.getUsername());
                            return (receiverIsUser1 && chat.isConnectedUser1()) || 
                                   (receiverIsUser2 && chat.isConnectedUser2()) ||
                                   chat.isConnectedUser1() || chat.isConnectedUser2(); // Or any active chat
                        })
                        .findFirst();
                
                if (chatOpt.isPresent()) {
                    var chat = chatOpt.get();
                    log.info("Found chat {} for system message to receiver {}", chat.getId(), message.getReceiver());
                    // Send message through Kafka (asynchronous)
                    chatService.sendMessage(chat.getId(), message);
                } else {
                    log.warn("No active chat found for system message receiver: {}", message.getReceiver());
                }
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                log.error("Receiver not found for system message: {}", message.getReceiver(), e);
            }
            return;
        }
        
        // For regular messages, find chat by sender and receiver
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

