package dora.server.chat;

import dora.server.auth.User;
import dora.server.contact.Contact;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    public enum ChatStatus {
        CREATED,
        CONNECTED,
        DISCONNECTED
    }
}

