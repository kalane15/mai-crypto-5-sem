package dora.crypto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity representing a chat message stored locally in the client database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalMessage {
    private Long id;
    private Long chatId;
    private String sender;
    private String receiver;
    private String message;
    private String type; // "TEXT", "ENCRYPTED", "FILE", "ENCRYPTED_FILE"
    private LocalDateTime timestamp;
    private String fileId; // Server file ID (for reference)
    private String localFilePath; // Local path to downloaded file
}

