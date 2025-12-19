package dora.server.contact;

import dora.crypto.shared.dto.ContactRequest;
import dora.crypto.shared.dto.Contact;
import dora.server.auth.User;
import dora.server.auth.UserService;
import dora.server.chat.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContactService {
    private final ContactRepository contactRepository;
    private final UserService userService;
    private final ChatRepository chatRepository;

    public Contact addContact(ContactRequest request) {
        User currentUser = userService.getCurrentUser();
        
        User contactUser;
        try {
            contactUser = userService.getByUsername(request.getUsername());
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            throw new IllegalArgumentException("User not found: " + request.getUsername());
        }

        if (currentUser.getUsername().equals(contactUser.getUsername())) {
            throw new IllegalArgumentException("Cannot add yourself as a contact");
        }

        if (contactRepository.existsByUserAndContactUser(currentUser, contactUser)) {
            throw new IllegalArgumentException("Contact already exists");
        }

        if (contactRepository.existsByUserAndContactUser(contactUser, currentUser)) {
            throw new IllegalArgumentException("Contact request already exists from this user");
        }

        dora.server.contact.Contact contact = dora.server.contact.Contact.builder()
                .user(currentUser)
                .contactUser(contactUser)
                .status(dora.server.contact.Contact.ContactStatus.PENDING)
                .build();

        contact = contactRepository.save(contact);
        return toDto(contact);
    }

    public List<Contact> getContacts() {
        User currentUser = userService.getCurrentUser();
        List<dora.server.contact.Contact> sentContacts = contactRepository.findByUser(currentUser)
                .stream()
                .filter((contact) ->
                {
                    return contact.getStatus() == dora.server.contact.Contact.ContactStatus.PENDING;
                }).toList();
        List<dora.server.contact.Contact> receivedContacts = contactRepository.findByContactUser(currentUser);

        List<dora.server.contact.Contact> allContacts = new java.util.ArrayList<>();
        allContacts.addAll(sentContacts);
        allContacts.addAll(receivedContacts);

        return allContacts.stream()
                .map(contact -> toDto(contact, currentUser))
                .collect(Collectors.toList());
    }

    public List<Contact> getPendingContacts() {
        User currentUser = userService.getCurrentUser();
        List<dora.server.contact.Contact> contacts = contactRepository.findByContactUserAndStatus(
                currentUser, dora.server.contact.Contact.ContactStatus.PENDING);
        return contacts.stream()
                .map(contact -> toDto(contact, currentUser))
                .collect(Collectors.toList());
    }

    @Transactional
    public void confirmContact(Long contactId) {
        User currentUser = userService.getCurrentUser();
        dora.server.contact.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        if (!contact.getContactUser().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only confirm contacts sent to you");
        }

        if (contact.getStatus() != dora.server.contact.Contact.ContactStatus.PENDING) {
            throw new IllegalArgumentException("Contact is not in PENDING status");
        }

        contact.setStatus(dora.server.contact.Contact.ContactStatus.CONFIRMED);
        contactRepository.save(contact);

        if (!contactRepository.existsByUserAndContactUser(contact.getContactUser(), contact.getUser())) {
            dora.server.contact.Contact reverseContact = dora.server.contact.Contact.builder()
                    .user(contact.getContactUser())
                    .contactUser(contact.getUser())
                    .status(dora.server.contact.Contact.ContactStatus.CONFIRMED)
                    .build();
            contactRepository.save(reverseContact);
        }
    }

    @Transactional
    public void rejectContact(Long contactId) {
        User currentUser = userService.getCurrentUser();
        dora.server.contact.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        if (!contact.getContactUser().getUsername().equals(currentUser.getUsername())) {
            throw new IllegalArgumentException("You can only reject contacts sent to you");
        }

        if (contact.getStatus() != dora.server.contact.Contact.ContactStatus.PENDING) {
            throw new IllegalArgumentException("Contact is not in PENDING status");
        }

        contactRepository.delete(contact);
    }

    @Transactional
    public void deleteContact(Long contactId) {
        User currentUser = userService.getCurrentUser();
        dora.server.contact.Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        boolean isSender = contact.getUser().getUsername().equals(currentUser.getUsername());
        boolean isRecipient = contact.getContactUser().getUsername().equals(currentUser.getUsername());
        
        if (!isSender && !isRecipient) {
            throw new IllegalArgumentException("You can only delete contacts that involve you");
        }

        List<dora.server.chat.Chat> chatsWithContact = chatRepository.findByContact_Id(contactId);
        if (!chatsWithContact.isEmpty()) {
            chatRepository.deleteAll(chatsWithContact);
        }

        contactRepository.delete(contact);

        contactRepository.findByUserAndContactUser(contact.getContactUser(), contact.getUser())
                .ifPresent(reverseContact -> {
                    List<dora.server.chat.Chat> chatsWithReverseContact = chatRepository.findByContact_Id(reverseContact.getId());
                    if (!chatsWithReverseContact.isEmpty()) {
                        chatRepository.deleteAll(chatsWithReverseContact);
                    }
                    contactRepository.delete(reverseContact);
                });
    }

    private Contact toDto(dora.server.contact.Contact contact) {
        return Contact.builder()
                .id(contact.getId())
                .username(contact.getContactUser().getUsername())
                .status(contact.getStatus().name())
                .isSentByMe(true)
                .build();
    }

    private Contact toDto(dora.server.contact.Contact contact, User currentUser) {
        String otherUsername;
        boolean isSentByMe;
        if (contact.getUser().getUsername().equals(currentUser.getUsername())) {
            otherUsername = contact.getContactUser().getUsername();
            isSentByMe = true;
        } else {
            otherUsername = contact.getUser().getUsername();
            isSentByMe = false;
        }

        return Contact.builder()
                .id(contact.getId())
                .username(otherUsername)
                .status(contact.getStatus().name())
                .isSentByMe(isSentByMe)
                .build();
    }
}
