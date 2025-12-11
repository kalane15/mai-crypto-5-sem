package dora.crypto.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
public class ChatKeyPart {
    @JsonProperty("keypart")
    private BigInteger keypart;
}
