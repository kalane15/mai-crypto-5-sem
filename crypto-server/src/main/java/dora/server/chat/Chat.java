package dora.server.chat;

import dora.server.auth.User;
import dora.server.contact.Contact;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;

@Entity
@Table(name = "chat")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user1_id", nullable = false)
    private User user1;

    @ManyToOne
    @JoinColumn(name = "user2_id", nullable = false)
    private User user2;

    @ManyToOne
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @Column(nullable = false)
    private String algorithm;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String padding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatStatus status;

    @Column(precision = 400, scale = 0)
    private BigInteger p;

    @Column(nullable = false)
    private BigInteger g;

    public enum ChatStatus {
        CREATED,
        CONNECTED,
        DISCONNECTED
    }

    @PrePersist
    private void prePersist() {
        if (this.p == null) {
            // случайное число порядка 10^300
            String big = "1" + "0".repeat(300);
            BigInteger base = new BigInteger(big);
            BigInteger randomPart = BigInteger.valueOf((long) (Math.random() * 1000));
            this.p = base.add(randomPart);
        }
        this.g = BigInteger.valueOf((int) (Math.random() * 10) + 2);
    }
}

