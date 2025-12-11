package dora.server.contact;

import dora.crypto.shared.dto.Contact;
import dora.crypto.shared.dto.ContactRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/contacts")
@RequiredArgsConstructor
@Tag(name = "Contact Management")
public class ContactController {
    private final ContactService contactService;

    @Operation(summary = "Add a new contact")
    @PostMapping
    public ResponseEntity<Contact> addContact(@RequestBody @Valid ContactRequest request) {
        try {
            Contact contact = contactService.addContact(request);
            return ResponseEntity.ok(contact);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Get all contacts")
    @GetMapping
    public ResponseEntity<List<Contact>> getContacts() {
        List<Contact> contacts = contactService.getContacts();
        return ResponseEntity.ok(contacts);
    }

    @Operation(summary = "Confirm a pending contact request")
    @PostMapping("/{contactId}/confirm")
    public ResponseEntity<Void> confirmContact(@PathVariable("contactId") Long contactId) {
        try {
            contactService.confirmContact(contactId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Reject a pending contact request")
    @PostMapping("/{contactId}/reject")
    public ResponseEntity<Void> rejectContact(@PathVariable("contactId") Long contactId) {
        try {
            contactService.rejectContact(contactId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Delete a contact")
    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> deleteContact(@PathVariable("contactId") Long contactId) {
        try {
            contactService.deleteContact(contactId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}

