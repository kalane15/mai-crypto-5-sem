package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Secret chat information")
public class Chat {
    @Schema(description = "Chat ID")
    @JsonProperty("id")
    private Long id;

    @Schema(description = "Contact username", example = "user123")
    @JsonProperty("contactUsername")
    private String contactUsername;

    @Schema(description = "Encryption algorithm", example = "DES")
    @JsonProperty("algorithm")
    private String algorithm;

    @Schema(description = "Encryption mode", example = "CBC")
    @JsonProperty("mode")
    private String mode;

    @Schema(description = "Padding mode", example = "PKCS7")
    @JsonProperty("padding")
    private String padding;

    @Schema(description = "Chat status", example = "CONNECTED", allowableValues = {"CREATED", "CONNECTED", "DISCONNECTED"})
    @JsonProperty("status")
    private String status; // CREATED, CONNECTED, DISCONNECTED
}

