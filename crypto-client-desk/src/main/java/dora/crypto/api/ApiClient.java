package dora.crypto.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dora.crypto.shared.dto.AuthResponse;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatRequest;
import dora.crypto.shared.dto.Contact;
import dora.crypto.shared.dto.ContactRequest;
import dora.crypto.shared.dto.FileInfo;
import dora.crypto.shared.dto.FileUploadResponse;
import dora.crypto.shared.dto.SignInRequest;
import dora.crypto.shared.dto.SignUpRequest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ApiClient {
    public static final String BASE_URL = "http://localhost:8080";
    public static final String BASE_URL_NO_PROTOCOL = "//localhost:8080";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String authToken; // Token stored only in RAM
    private Consumer<String> onTokenInvalidCallback; // Callback when token is invalid

    public ApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sets a callback to be invoked when the token becomes invalid (401/403).
     * The callback receives the error message.
     */
    public void setOnTokenInvalid(Consumer<String> callback) {
        this.onTokenInvalidCallback = callback;
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public void clearAuthToken() {
        this.authToken = null;
    }

    public String getAuthToken() {
        return authToken;
    }

    private HttpRequest.Builder createRequestBuilder(String endpoint) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(30));

        if (authToken != null && !authToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + authToken);
            System.out.println("DEBUG: Sending request to " + endpoint + " with Authorization header");
        } else {
            System.err.println("WARNING: No auth token available for request to " + endpoint);
        }

        return builder;
    }

    /**
     * Extracts error message from response body, handling JSON and plain text responses.
     */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return null;
        }
        
        try {
            // Try to parse as JSON and extract message field
            var jsonNode = objectMapper.readTree(responseBody);
            if (jsonNode.has("message")) {
                return jsonNode.get("message").asText();
            }
            if (jsonNode.has("error")) {
                return jsonNode.get("error").asText();
            }
            // If it's JSON but no message field, return the whole body as string
            return responseBody;
        } catch (Exception e) {
            // Not JSON, return as-is (might be plain text error message)
            return responseBody;
        }
    }

    private boolean handleUnauthorized(int statusCode, String errorMessage) {
        if (statusCode == 401 || statusCode == 403) {
            System.err.println("Received " + statusCode + " - clearing invalid token");
            clearAuthToken();
            
            // Notify callback if set
            if (onTokenInvalidCallback != null) {
                String message = errorMessage != null ? errorMessage : 
                    (statusCode == 401 ? "Unauthorized - please sign in again" : "Forbidden - please sign in again");
                onTokenInvalidCallback.accept(message);
            }
            
            return true;
        }
        return false;
    }

    // Authentication
    public CompletableFuture<AuthResponse> signUp(String username, String password) {
        try {
            SignUpRequest request = new SignUpRequest(username, password);
            String jsonBody = objectMapper.writeValueAsString(request);

            // Auth endpoints don't need tokens
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/auth/sign-up"))
                    .timeout(Duration.ofSeconds(30))
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
                            // Extract error message from response body if available
                            String errorMessage = extractErrorMessage(response.body());
                            throw new RuntimeException(errorMessage != null ? errorMessage : "Sign up failed");
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

            // Auth endpoints don't need tokens
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/auth/sign-in"))
                    .timeout(Duration.ofSeconds(30))
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
                            // Extract error message from response body if available
                            String errorMessage = extractErrorMessage(response.body());
                            throw new RuntimeException(errorMessage != null ? errorMessage : "Sign in failed");
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
                        if (handleUnauthorized(response.statusCode(), response.body())) {
                            throw new RuntimeException("Unauthorized - please sign in again");
                        }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                        if (handleUnauthorized(response.statusCode(), response.body())) {
                            throw new RuntimeException("Unauthorized - please sign in again");
                        }
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            try {
                                return objectMapper.readValue(response.body(), Chat.class);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to parse chat response", e);
                            }
                        } else {
                            // Extract user-friendly error message from response
                            String errorMessage = extractErrorMessage(response.body());
                            if (errorMessage != null && !errorMessage.isEmpty()) {
                                throw new RuntimeException(errorMessage);
                            } else {
                                throw new RuntimeException("Failed to create chat. Please try again.");
                            }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
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
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Disconnect from chat failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    public CompletableFuture<Void> deleteChat(Long chatId) {
        HttpRequest httpRequest = createRequestBuilder("/chats/" + chatId + "/delete")
                .DELETE()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return null;
                    } else {
                        throw new RuntimeException("Delete chat failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }

    // File operations
    public CompletableFuture<FileUploadResponse> uploadFile(File file) {
        try {
            // Read file into byte array
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();
            
            // Create multipart form data
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            String CRLF = "\r\n";
            
            StringBuilder formData = new StringBuilder();
            formData.append("--").append(boundary).append(CRLF);
            formData.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"").append(CRLF);
            formData.append("Content-Type: application/octet-stream").append(CRLF);
            formData.append(CRLF);
            
            // Combine form data header and file content
            byte[] headerBytes = formData.toString().getBytes("UTF-8");
            byte[] footerBytes = (CRLF + "--" + boundary + "--" + CRLF).getBytes("UTF-8");
            
            byte[] requestBody = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, requestBody, 0, headerBytes.length);
            System.arraycopy(fileBytes, 0, requestBody, headerBytes.length, fileBytes.length);
            System.arraycopy(footerBytes, 0, requestBody, headerBytes.length + fileBytes.length, footerBytes.length);

            HttpRequest httpRequest = createRequestBuilder("/files/upload")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .build();

            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (handleUnauthorized(response.statusCode(), response.body())) {
                            throw new RuntimeException("Unauthorized - please sign in again");
                        }
                        if (response.statusCode() == 200) {
                            try {
                                return objectMapper.readValue(response.body(), FileUploadResponse.class);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to parse file upload response", e);
                            }
                        } else {
                            throw new RuntimeException("File upload failed: " + response.statusCode() + " - " + response.body());
                        }
                    });
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<File> downloadFile(String fileId, File outputFile) {
        HttpRequest httpRequest = createRequestBuilder("/files/" + fileId)
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (handleUnauthorized(response.statusCode(), null)) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
                    if (response.statusCode() == 200) {
                        try {
                            // Ensure parent directory exists
                            if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
                                outputFile.getParentFile().mkdirs();
                            }
                            // Write bytes to file
                            Files.write(outputFile.toPath(), response.body());
                            return outputFile;
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to write file: " + e.getMessage(), e);
                        }
                    } else {
                        throw new RuntimeException("File download failed: " + response.statusCode());
                    }
                });
    }

    public CompletableFuture<byte[]> downloadFileBytes(String fileId) {
        HttpRequest httpRequest = createRequestBuilder("/files/" + fileId)
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (handleUnauthorized(response.statusCode(), null)) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
                    if (response.statusCode() == 200) {
                        return response.body();
                    } else {
                        throw new RuntimeException("File download failed: " + response.statusCode());
                    }
                });
    }

    public CompletableFuture<FileInfo> getFileInfo(String fileId) {
        HttpRequest httpRequest = createRequestBuilder("/files/" + fileId + "/info")
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (handleUnauthorized(response.statusCode(), response.body())) {
                        throw new RuntimeException("Unauthorized - please sign in again");
                    }
                    if (response.statusCode() == 200) {
                        try {
                            return objectMapper.readValue(response.body(), FileInfo.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse file info", e);
                        }
                    } else {
                        throw new RuntimeException("Get file info failed: " + response.statusCode() + " - " + response.body());
                    }
                });
    }
}

