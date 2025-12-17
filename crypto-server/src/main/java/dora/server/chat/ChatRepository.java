package dora.server.chat;

import dora.server.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {
    Optional<Chat> findById(Long id);

    List<Chat> findByUser1OrUser2(User user1, User user2);
    
    Optional<Chat> findByUser1AndUser2(User user1, User user2);
    
    Optional<Chat> findByUser2AndUser1(User user1, User user2);
    
    List<Chat> findByUser1(User user1);
    
    List<Chat> findByUser2(User user2);
    
    List<Chat> findByContact_Id(Long contactId);
}

