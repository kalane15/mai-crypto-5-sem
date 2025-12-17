package dora.crypto.shared.dto;

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
@Schema(description = "Contact information")
public class Contact {
    @Schema(description = "Contact ID")
    @JsonProperty("id")
    private Long id;

    @Schema(description = "Contact username", example = "user123")
    @JsonProperty("username")
    private String username;

    @Schema(description = "Contact status", example = "CONFIRMED", allowableValues = {"PENDING", "CONFIRMED", "REJECTED"})
    @JsonProperty("status")
    private String status; // PENDING, CONFIRMED, REJECTED

    @Schema(description = "Whether this contact request was sent by the current user")
    @JsonProperty("isSentByMe")
    private Boolean isSentByMe; // true if current user sent the request, false if received
}

