package dora.crypto.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dora.crypto.shared.dto.AuthResponse;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatRequest;
import dora.crypto.shared.dto.Contact;
import dora.crypto.shared.dto.ContactRequest;
import dora.crypto.shared.dto.SignInRequest;
import dora.crypto.shared.dto.SignUpRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ApiClient {
    public static final String BASE_URL = "http://localhost:8080";
    public static final String BASE_URL_NO_PROTOCOL = "//localhost:8080";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String authToken;

    public ApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public void clearAuthToken() {
        this.authToken = null;
    }

    private HttpRequest.Builder createRequestBuilder(String endpoint) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(30));

        if (authToken != null && !authToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + authToken);
        } else {
            System.err.println("WARNING: No auth token available for request to " + endpoint);
        }

        return builder;
    }

    // Authentication
    public CompletableFuture<AuthResponse> signUp(String username, String password) {
        try {
            SignUpRequest request = new SignUpRequest(username, password);
            String jsonBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = createRequestBuilder("/auth/sign-up")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                return objectMapper.readValue(response.body(), AuthResponse.class);
                            } catch (Exception e) {
                                System.out.println(e.getMessage());
                                throw new RuntimeException("Failed to parse response", e);
                            }
                        } else {
                            throw new RuntimeException("Sign up failed: " + response.statusCode() + " - " + response.body());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<AuthResponse> signIn(String username, String password) {
        try {
            SignInRequest request = new SignInRequest(username, password);
            String jsonBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = createRequestBuilder("/auth/sign-in")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                return objectMapper.readValue(response.body(), AuthResponse.class);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to parse response", e);
                            }
                        } else {
                            throw new RuntimeException("Sign in failed: " + response.statusCode() + " - " + response.body());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // Contacts
    public CompletableFuture<Void> addContact(String username) {
        try {
            ContactRequest request = new ContactRequest(username);
            String jsonBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = createRequestBuilder("/contacts")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return null;
                        } else {
                            throw new RuntimeException("Add contact failed: " + response.statusCode() + " - " + response.body());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<List<Contact>> getContacts() {
        HttpRequest httpRequest = createRequestBuilder("/contacts")
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            return objectMapper.readValue(
                                    response.body(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Contact.class)
                            );
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse contacts", e);
                        }
                    } else {
                        throw new RuntimeException("Get contacts failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> confirmContact(Long contactId) {
        HttpRequest httpRequest = createRequestBuilder("/contacts/" + contactId + "/confirm")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Confirm contact failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> rejectContact(Long contactId) {
        HttpRequest httpRequest = createRequestBuilder("/contacts/" + contactId + "/reject")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Reject contact failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> deleteContact(Long contactId) {
        HttpRequest httpRequest = createRequestBuilder("/contacts/" + contactId)
                .DELETE()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Delete contact failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    // Chats
    public CompletableFuture<Chat> createChat(Long contactId, String algorithm, String mode, String padding) {
        try {
            ChatRequest request = new ChatRequest(contactId, algorithm, mode, padding);
            String jsonBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = createRequestBuilder("/chats")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                return objectMapper.readValue(response.body(), Chat.class);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to parse chat response", e);
                            }
                        } else {
                            throw new RuntimeException("Create chat failed: " + response.statusCode() + " - " + response.body());
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<List<Chat>> getChats() {
        HttpRequest httpRequest = createRequestBuilder("/chats")
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            return objectMapper.readValue(
                                    response.body(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Chat.class)
                            );
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse chats", e);
                        }
                    } else {
                        throw new RuntimeException("Get chats failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> connectToChat(Long chatId) {
        HttpRequest httpRequest = createRequestBuilder("/chats/" + chatId + "/connect")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Connect to chat failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> disconnectFromChat(Long chatId) {
        HttpRequest httpRequest = createRequestBuilder("/chats/" + chatId + "/disconnect")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Disconnect from chat failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }
}

