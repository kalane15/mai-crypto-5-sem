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

        User otherUser = contact.getUser().getUsername().equals(currentUser.getUsername())
                ? contact.getContactUser()
                : contact.getUser();

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

        boolean alreadyConnected = (chat.getUser1().getUsername().equals(currentUser.getUsername()) && chat.isConnectedUser1()) ||
                                   (chat.getUser2().getUsername().equals(currentUser.getUsername()) && chat.isConnectedUser2());

        if (alreadyConnected) {
            throw new IllegalArgumentException("Dont connect twice to one chat");
        }

        if (chat.getUser1().getUsername().equals(currentUser.getUsername())) {
            chat.setConnectedUser1(true);
        } else {
            chat.setConnectedUser2(true);
        }

        int connectedCount = (chat.isConnectedUser1() ? 1 : 0) + (chat.isConnectedUser2() ? 1 : 0);
        var oldStatus = chat.getStatus();
        dora.server.chat.Chat.ChatStatus newStatus;
        if (connectedCount == 0) {
            newStatus = dora.server.chat.Chat.ChatStatus.CREATED;
        } else if (connectedCount == 1) {
            newStatus = dora.server.chat.Chat.ChatStatus.CONNECTED1;
        } else {
            newStatus = dora.server.chat.Chat.ChatStatus.CONNECTED2;
        }
        chat.setStatus(newStatus);

        System.out.println("Chat " + chat.getId() + " status: " + oldStatus.name() + " -> " + newStatus.name() +
                          " (user1 connected: " + chat.isConnectedUser1() +
                          ", user2 connected: " + chat.isConnectedUser2() + ")");

        chatRepository.save(chat);

        User otherUser = chat.getUser1().getUsername().equals(currentUser.getUsername())
                ? chat.getUser2()
                : chat.getUser1();

        if (newStatus == dora.server.chat.Chat.ChatStatus.CONNECTED1) {
            System.out.println("Chat reached CONNECTED1, sending system message to waiting user");
            var systemMessage = ChatMessage.builder()
                    .receiver(currentUser.getUsername())
                    .sender("System")
                    .message("You are connected. Waiting for the other user to join...")
                    .type("SYSTEM")
                    .build();
            sendMessage(chat.getId(), systemMessage);
        }

        if (newStatus == dora.server.chat.Chat.ChatStatus.CONNECTED2) {
            System.out.println("Chat reached CONNECTED2, sending system messages and 'ready for key exchange' messages to both users");

            var systemMessageToOther = ChatMessage.builder()
                    .receiver(otherUser.getUsername())
                    .sender("System")
                    .message("The other user has joined the chat. Key exchange in progress...")
                    .type("SYSTEM")
                    .build();
            sendMessage(chat.getId(), systemMessageToOther);

            var systemMessageToCurrent = ChatMessage.builder()
                    .receiver(currentUser.getUsername())
                    .sender("System")
                    .message("You have joined the chat. Key exchange in progress...")
                    .type("SYSTEM")
                    .build();
            sendMessage(chat.getId(), systemMessageToCurrent);

            var messageToOther = ChatMessage.builder()
                    .receiver(otherUser.getUsername())
                    .sender(currentUser.getUsername())
                    .message("ready for key exchange")
                    .build();
            sendMessage(chat.getId(), messageToOther);

            var messageToCurrent = ChatMessage.builder()
                    .receiver(currentUser.getUsername())
                    .sender(otherUser.getUsername())
                    .message("ready for key exchange")
                    .build();
            sendMessage(chat.getId(), messageToCurrent);
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

        boolean isUser1 = chat.getUser1().getUsername().equals(currentUser.getUsername());
        boolean isUser2 = chat.getUser2().getUsername().equals(currentUser.getUsername());

        boolean isConnected = (isUser1 && chat.isConnectedUser1()) || (isUser2 && chat.isConnectedUser2());

        if (!isConnected) {
            throw new IllegalArgumentException("You are not connected to this chat");
        }

        if (isUser1) {
            chat.setConnectedUser1(false);
        } else if (isUser2) {
            chat.setConnectedUser2(false);
        }

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

        if (connectedCount == 1) {
            User remainingUser = chat.isConnectedUser1() ? chat.getUser1() : chat.getUser2();
            System.out.println("User disconnected, sending system message to remaining user: " + remainingUser.getUsername());
            var systemMessage = ChatMessage.builder()
                    .receiver(remainingUser.getUsername())
                    .sender("System")
                    .message("The other user has disconnected from the chat.")
                    .type("SYSTEM")
                    .build();
            sendMessage(chat.getId(), systemMessage);
        }
    }

    @Transactional
    public void deleteChat(Long chatId) {
        User currentUser = userService.getCurrentUser();
        dora.server.chat.Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found"));

        if (!chat.getUser1().getUsername().equals(currentUser.getUsername()) &&
                !chat.getUser2().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only delete your own chats");
        }

        User user1 = chat.getUser1();
        User user2 = chat.getUser2();

        boolean user1WasConnected = chat.isConnectedUser1();
        boolean user2WasConnected = chat.isConnectedUser2();

        if (user1WasConnected) {
            var messageToUser1 = ChatMessage.builder()
                    .receiver(user1.getUsername())
                    .sender("System")
                    .message("chat deleted")
                    .type("SYSTEM")
                    .build();
            sendMessage(chatId, messageToUser1);
        }

        if (user2WasConnected) {
            var messageToUser2 = ChatMessage.builder()
                    .receiver(user2.getUsername())
                    .sender("System")
                    .message("chat deleted")
                    .type("SYSTEM")
                    .build();
            sendMessage(chatId, messageToUser2);
        }

        chat.setConnectedUser1(false);
        chat.setConnectedUser2(false);
        chat.setStatus(dora.server.chat.Chat.ChatStatus.CREATED);
        chat = chatRepository.save(chat);

        if (chat.isConnectedUser1() || chat.isConnectedUser2()) {
            throw new IllegalStateException("Failed to disconnect all users from chat " + chatId +
                                         " - user1: " + chat.isConnectedUser1() +
                                         ", user2: " + chat.isConnectedUser2());
        }

        System.out.println("Successfully disconnected all users from chat " + chatId + " before deletion " +
                         "(user1 was connected: " + user1WasConnected +
                         ", user2 was connected: " + user2WasConnected +
                         ", user1 now connected: " + chat.isConnectedUser1() +
                         ", user2 now connected: " + chat.isConnectedUser2() + ")");

        chatRepository.delete(chat);
        System.out.println("Chat " + chatId + " deleted successfully");
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

