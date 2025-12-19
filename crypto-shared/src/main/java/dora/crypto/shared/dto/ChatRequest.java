package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to create a new secret chat")
public class ChatRequest {
    @Schema(description = "Contact ID to create chat with")
    @NotNull(message = "Contact ID cannot be null")
    @JsonProperty("contactId")
    private Long contactId;

    @Schema(description = "Encryption algorithm", example = "MARS", allowableValues = {"MARS", "RC5"})
    @JsonProperty("algorithm")
    private String algorithm;

    @Schema(description = "Encryption mode", example = "CBC", allowableValues = {"CBC", "CFB", "CTR", "ECB", "OFB", "PCBC", "RANDOM_DELTA"})
    @JsonProperty("mode")
    private String mode;

    @Schema(description = "Padding mode", example = "PKCS7", allowableValues = {"ANSI_X923", "ISO_10126", "PKCS7", "ZEROS"})
    @JsonProperty("padding")
    private String padding;

    @Schema(description = "RC5 word size (16, 32, or 64 bits)", example = "32")
    @JsonProperty("rc5WordSize")
    private Integer rc5WordSize;

    @Schema(description = "RC5 number of rounds (1-255)", example = "12")
    @JsonProperty("rc5Rounds")
    private Integer rc5Rounds;

    public ChatRequest() {
    }

    public ChatRequest(Long contactId, String algorithm, String mode, String padding) {
        this.contactId = contactId;
        this.algorithm = algorithm;
        this.mode = mode;
        this.padding = padding;
    }

    public ChatRequest(Long contactId, String algorithm, String mode, String padding, Integer rc5WordSize, Integer rc5Rounds) {
        this.contactId = contactId;
        this.algorithm = algorithm;
        this.mode = mode;
        this.padding = padding;
        this.rc5WordSize = rc5WordSize;
        this.rc5Rounds = rc5Rounds;
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

    public Integer getRc5WordSize() {
        return rc5WordSize;
    }

    public void setRc5WordSize(Integer rc5WordSize) {
        this.rc5WordSize = rc5WordSize;
    }

    public Integer getRc5Rounds() {
        return rc5Rounds;
    }

    public void setRc5Rounds(Integer rc5Rounds) {
        this.rc5Rounds = rc5Rounds;
    }
}

