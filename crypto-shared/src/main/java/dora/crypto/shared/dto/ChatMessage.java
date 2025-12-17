package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    @JsonProperty("sender")
    private String sender;

    @JsonProperty("receiver")
    private String receiver;

    @JsonProperty("message")
    private String message;

    @JsonProperty("type")
    private String type; // "TEXT", "ENCRYPTED", "FILE", "ENCRYPTED_FILE"
}
