package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to create a new secret chat")
public class ChatRequest {
    @Schema(description = "Contact ID to create chat with")
    @NotNull(message = "Contact ID cannot be null")
    @JsonProperty("contactId")
    private Long contactId;

    @Schema(description = "Encryption algorithm", example = "DES", allowableValues = {"DES", "DEAL", "RC5", "RIJNDAEL"})
    @JsonProperty("algorithm")
    private String algorithm;

    @Schema(description = "Encryption mode", example = "CBC", allowableValues = {"CBC", "CFB", "CTR", "ECB", "OFB", "PCBC", "RANDOM_DELTA"})
    @JsonProperty("mode")
    private String mode;

    @Schema(description = "Padding mode", example = "PKCS7", allowableValues = {"ANSI_X923", "ISO_10126", "PKCS7", "ZEROS"})
    @JsonProperty("padding")
    private String padding;

    public ChatRequest() {
    }

    public ChatRequest(Long contactId, String algorithm, String mode, String padding) {
        this.contactId = contactId;
        this.algorithm = algorithm;
        this.mode = mode;
        this.padding = padding;
    }

    public Long getContactId() {
        return contactId;
    }

    public void setContactId(Long contactId) {
        this.contactId = contactId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getPadding() {
        return padding;
    }

    public void setPadding(String padding) {
        this.padding = padding;
    }
}

