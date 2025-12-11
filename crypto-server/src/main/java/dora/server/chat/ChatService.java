package dora.server.chat;

import dora.crypto.shared.dto.ChatRequest;
import dora.crypto.shared.dto.Chat;
import dora.server.auth.User;
import dora.server.auth.UserService;
import dora.server.contact.ContactRepository;
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

        if (chat.getStatus() == dora.server.chat.Chat.ChatStatus.CONNECTED) {
            throw new IllegalArgumentException("Chat is already connected");
        }

        chat.setStatus(dora.server.chat.Chat.ChatStatus.CONNECTED);
        chatRepository.save(chat);
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

        if (chat.getStatus() != dora.server.chat.Chat.ChatStatus.CONNECTED) {
            throw new IllegalArgumentException("Chat is not connected");
        }

        chat.setStatus(dora.server.chat.Chat.ChatStatus.DISCONNECTED);
        chatRepository.save(chat);
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

