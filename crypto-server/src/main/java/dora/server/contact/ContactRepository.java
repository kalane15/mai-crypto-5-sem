package dora.server.contact;

import dora.server.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {
    List<Contact> findByUser(User user);
    
    List<Contact> findByContactUser(User contactUser);
    
    Optional<Contact> findByUserAndContactUser(User user, User contactUser);
    
    List<Contact> findByContactUserAndStatus(User contactUser, Contact.ContactStatus status);
    
    boolean existsByUserAndContactUser(User user, User contactUser);
}

