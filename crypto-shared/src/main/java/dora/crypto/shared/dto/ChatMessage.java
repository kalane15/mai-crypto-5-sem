package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigInteger;

@Builder
@Data
public class ChatMessage {
    @JsonProperty("message")
    private String message;
}
