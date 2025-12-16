package dora.server.chat;

import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.ChatKeyPart;
import dora.crypto.shared.dto.ChatRequest;
import dora.crypto.shared.dto.Chat;
import dora.server.auth.User;
import dora.server.auth.UserService;
import dora.server.contact.ContactRepository;
import dora.server.kafka.KafkaMessageProducer;
import dora.server.kafka.KafkaMessageWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatRepository chatRepository;
    private final ContactRepository contactRepository;
    private final UserService userService;
    private final KafkaMessageProducer kafkaMessageProducer;

    public Chat createChat(ChatRequest request) {
        User currentUser = userService.getCurrentUser();
        dora.server.contact.Contact contact = contactRepository.findById(request.getContactId())
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        if (!contact.getUser().getUsername().equals(currentUser.getUsername()) &&
                !contact.getContactUser().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only create chats with your contacts");
        }

        if (contact.getStatus() != dora.server.contact.Contact.ContactStatus.CONFIRMED) {
            throw new IllegalArgumentException("Contact must be confirmed to create a chat");
        }

        // Determine the other user
        User otherUser = contact.getUser().getUsername().equals(currentUser.getUsername())
                ? contact.getContactUser()
                : contact.getUser();

        // Check if chat already exists
        Optional<dora.server.chat.Chat> existingChat = chatRepository.findByUser1AndUser2(currentUser, otherUser);
        if (existingChat.isEmpty()) {
            existingChat = chatRepository.findByUser2AndUser1(currentUser, otherUser);
        }

        if (existingChat.isPresent()) {
            throw new IllegalArgumentException("Chat already exists with this contact");
        }

        dora.server.chat.Chat chat = dora.server.chat.Chat.builder()
                .user1(currentUser)
                .user2(otherUser)
                .contact(contact)
                .algorithm(request.getAlgorithm())
                .mode(request.getMode())
                .padding(request.getPadding())
                .status(dora.server.chat.Chat.ChatStatus.CREATED)
                .build();

        chat = chatRepository.save(chat);
        return toDto(chat, otherUser);
    }

    public List<Chat> getChats() {
        User currentUser = userService.getCurrentUser();
        List<dora.server.chat.Chat> chats = chatRepository.findByUser1OrUser2(currentUser, currentUser);
        return chats.stream()
                .map(chat -> {
                    User otherUser = chat.getUser1().getUsername().equals(currentUser.getUsername())
                            ? chat.getUser2()
                            : chat.getUser1();
                    return toDto(chat, otherUser);
                })
                .collect(Collectors.toList());
    }

    public Chat getChat(Long id) {
        var res = chatRepository.findById(id);
        return toDto(res.orElseThrow());
    }

    @Transactional
    public void connectToChat(Long chatId) {
        User currentUser = userService.getCurrentUser();
        dora.server.chat.Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found"));

        if (!chat.getUser1().getUsername().equals(currentUser.getUsername()) &&
                !chat.getUser2().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only connect to your own chats");
        }

        // Check if already connected
        boolean alreadyConnected = (chat.getUser1().getUsername().equals(currentUser.getUsername()) && chat.isConnectedUser1()) ||
                                   (chat.getUser2().getUsername().equals(currentUser.getUsername()) && chat.isConnectedUser2());
        
        if (alreadyConnected) {
            throw new IllegalArgumentException("Dont connect twice to one chat");
        }

        // Mark the current user as connected
        if (chat.getUser1().getUsername().equals(currentUser.getUsername())) {
            chat.setConnectedUser1(true);
        } else {
            chat.setConnectedUser2(true);
        }

        var oldStatus = chat.getStatus();
        var newStatus = chat.getStatus().next(1);
        chat.setStatus(newStatus);
        
        System.out.println("Chat " + chat.getId() + " status: " + oldStatus.name() + " -> " + newStatus.name() + 
                          " (user1 connected: " + chat.isConnectedUser1() + 
                          ", user2 connected: " + chat.isConnectedUser2() + ")");

        chatRepository.save(chat);

        // Determine the other user
        User otherUser = chat.getUser1().getUsername().equals(currentUser.getUsername())
                ? chat.getUser2()
                : chat.getUser1();

        // If status becomes CONNECTED2, send messages to both users
        if (newStatus == dora.server.chat.Chat.ChatStatus.CONNECTED2) {
            System.out.println("Chat reached CONNECTED2, sending 'ready for key exchange' messages to both users");
            // Send message to the other user
            var messageToOther = ChatMessage.builder()
                    .receiver(otherUser.getUsername())
                    .sender(currentUser.getUsername())
                    .message("ready for key exchange")
                    .build();
            sendMessage(chat.getId(), messageToOther);
            
            // Also send message to current user (they should receive it when subscribed)
            var messageToCurrent = ChatMessage.builder()
                    .receiver(currentUser.getUsername())
                    .sender(otherUser.getUsername())
                    .message("ready for key exchange")
                    .build();
            sendMessage(chat.getId(), messageToCurrent);
        } else if (oldStatus == dora.server.chat.Chat.ChatStatus.CONNECTED2) {
            // If status was already CONNECTED2, send message to the newly connecting user
            // This handles the case where one user connects after the status is already CONNECTED2
            System.out.println("Chat already CONNECTED2, sending 'ready for key exchange' to newly connected user: " + currentUser.getUsername());
            var message = ChatMessage.builder()
                    .receiver(currentUser.getUsername())
                    .sender(otherUser.getUsername())
                    .message("ready for key exchange")
                    .build();
            sendMessage(chat.getId(), message);
        }
    }

    public void sendMessage(Long chatId, ChatMessage message) {
        System.out.println("Publishing ChatMessage to Kafka: chatId=" + chatId + ", Message: " + message);
        KafkaMessageWrapper wrapper = KafkaMessageWrapper.forChatMessage(chatId, message);
        kafkaMessageProducer.publishMessage(chatId, wrapper)
                .exceptionally(ex -> {
                    System.err.println("Failed to publish message to Kafka: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
    }

    public void sendKeyPart(Long chatId, ChatKeyPart keyPart) {
        System.out.println("Publishing ChatKeyPart to Kafka: chatId=" + chatId + ", Sender: " + keyPart.getSender() + ", Receiver: " + keyPart.getReceiver());
        KafkaMessageWrapper wrapper = KafkaMessageWrapper.forKeyPart(chatId, keyPart);
        kafkaMessageProducer.publishMessage(chatId, wrapper)
                .exceptionally(ex -> {
                    System.err.println("Failed to publish key part to Kafka: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
    }

    @Transactional
    public void disconnectFromChat(Long chatId) {
        User currentUser = userService.getCurrentUser();
        dora.server.chat.Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found"));

        if (!chat.getUser1().getUsername().equals(currentUser.getUsername()) &&
                !chat.getUser2().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only disconnect from your own chats");
        }

        // Check if the user is actually connected
        boolean isUser1 = chat.getUser1().getUsername().equals(currentUser.getUsername());
        boolean isUser2 = chat.getUser2().getUsername().equals(currentUser.getUsername());
        
        boolean isConnected = (isUser1 && chat.isConnectedUser1()) || (isUser2 && chat.isConnectedUser2());
        
        if (!isConnected) {
            throw new IllegalArgumentException("You are not connected to this chat");
        }

        // Mark the current user as disconnected
        if (isUser1) {
            chat.setConnectedUser1(false);
        } else if (isUser2) {
            chat.setConnectedUser2(false);
        }

        // Update status based on how many users are still connected
        int connectedCount = (chat.isConnectedUser1() ? 1 : 0) + (chat.isConnectedUser2() ? 1 : 0);
        dora.server.chat.Chat.ChatStatus newStatus;
        if (connectedCount == 0) {
            newStatus = dora.server.chat.Chat.ChatStatus.CREATED;
        } else if (connectedCount == 1) {
            newStatus = dora.server.chat.Chat.ChatStatus.CONNECTED1;
        } else {
            newStatus = dora.server.chat.Chat.ChatStatus.CONNECTED2;
        }
        
        chat.setStatus(newStatus);
        
        System.out.println("Chat " + chat.getId() + " status: " + chat.getStatus().name() + 
                          " (user1 connected: " + chat.isConnectedUser1() + 
                          ", user2 connected: " + chat.isConnectedUser2() + ")");
        
        chatRepository.save(chat);
    }

    @Transactional
    public void deleteChat(Long chatId) {
        User currentUser = userService.getCurrentUser();
        dora.server.chat.Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found"));

        // Verify that the current user is part of this chat
        if (!chat.getUser1().getUsername().equals(currentUser.getUsername()) &&
                !chat.getUser2().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only delete your own chats");
        }

        // Manually disconnect all users from the chat before deleting
        User user1 = chat.getUser1();
        User user2 = chat.getUser2();
        
        // Send "chat deleted" notification to all connected users
        if (chat.isConnectedUser1()) {
            var messageToUser1 = ChatMessage.builder()
                    .receiver(user1.getUsername())
                    .sender("SYSTEM")
                    .message("chat deleted")
                    .build();
            sendMessage(chatId, messageToUser1);
        }
        
        if (chat.isConnectedUser2()) {
            var messageToUser2 = ChatMessage.builder()
                    .receiver(user2.getUsername())
                    .sender("SYSTEM")
                    .message("chat deleted")
                    .build();
            sendMessage(chatId, messageToUser2);
        }

        // Disconnect all users by resetting connection flags
        if (chat.isConnectedUser1() || chat.isConnectedUser2()) {
            chat.setConnectedUser1(false);
            chat.setConnectedUser2(false);
            chat.setStatus(dora.server.chat.Chat.ChatStatus.CREATED);
            chatRepository.save(chat);
            System.out.println("Disconnected all users from chat " + chatId + " before deletion");
        }

        // Delete the chat
        chatRepository.delete(chat);
    }

    private Chat toDto(dora.server.chat.Chat chat, User otherUser) {
        return Chat.builder()
                .id(chat.getId())
                .contactUsername(otherUser.getUsername())
                .algorithm(chat.getAlgorithm())
                .mode(chat.getMode())
                .padding(chat.getPadding())
                .status(chat.getStatus().name())
                .g(chat.getG())
                .p(chat.getP())
                .build();
    }

    private Chat toDto(dora.server.chat.Chat chat) {
        return Chat.builder()
                .id(chat.getId())
                .contactUsername(chat.getUser1().getUsername())
                .algorithm(chat.getAlgorithm())
                .mode(chat.getMode())
                .padding(chat.getPadding())
                .status(chat.getStatus().name())
                .build();
    }
}

