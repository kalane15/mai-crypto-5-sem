package dora.server.chat;

import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
@Tag(name = "Secret Chat Management")
public class ChatController {
    private final ChatService chatService;

    @Operation(summary = "Create a new secret chat")
    @PostMapping
    public ResponseEntity<Chat> createChat(@RequestBody @Valid ChatRequest request) {
        try {
            Chat chat = chatService.createChat(request);
            return ResponseEntity.ok(chat);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get all chats")
    @GetMapping
    public ResponseEntity<List<Chat>> getChats() {
        List<Chat> chats = chatService.getChats();
        return ResponseEntity.ok(chats);
    }

    @Operation(summary = "Connect to a chat")
    @GetMapping("/{chatId}/get")
    public ResponseEntity<Chat> getChat(@PathVariable("chatId") Long chatId) {
        try {
            return ResponseEntity.ok(chatService.getChat(chatId));
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Connect to a chat")
    @PostMapping("/{chatId}/connect")
    public ResponseEntity<Void> connectToChat(@PathVariable("chatId") Long chatId) {
        try {
            chatService.connectToChat(chatId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Disconnect from a chat")
    @PostMapping("/{chatId}/disconnect")
    public ResponseEntity<Void> disconnectFromChat(@PathVariable("chatId") Long chatId) {
        try {
            chatService.disconnectFromChat(chatId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete a chat")
    @DeleteMapping("/{chatId}/delete")
    public ResponseEntity<Void> deleteChat(@PathVariable("chatId") Long chatId) {
        try {
            chatService.deleteChat(chatId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}

