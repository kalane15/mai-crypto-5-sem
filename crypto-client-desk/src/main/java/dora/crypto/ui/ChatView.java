package dora.crypto.ui;

import dora.crypto.EncryptionUtil;
import dora.crypto.Main;
import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.shared.dto.FileInfo;
import dora.crypto.shared.dto.FileUploadResponse;
import dora.crypto.SymmetricCipher;
import dora.crypto.db.MessageDatabase;
import dora.crypto.model.LocalMessage;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

public class ChatView extends BorderPane {
    private final ApiClient apiClient;
    private final Chat chat;
    private final Main app;
    private VBox messageContainer;
    private ScrollPane messageScrollPane;
    private TextField messageInput;
    private Button sendButton;
    private Button encryptButton;
    private Button cancelEncryptButton;
    private Button disconnectButton;
    private Label chatInfoLabel;
    private ProgressIndicator encryptionProgress;
    private Task<String> currentEncryptionTask;
    private TextField ivInputField;
    private Button generateIVButton;
    private Label ivLabel;
    private byte[] currentIV;
    private Button attachFileButton;
    private Button encryptFileButton;
    private Button sendFileButton;
    private Button cancelFileEncryptionButton;
    private ProgressBar fileEncryptionProgress;
    private Label fileStatusLabel;
    private File selectedFile;
    private Path encryptedFileWithIV; // Store encrypted file path after encryption
    private Task<Path> currentFileEncryptionTask; // Changed to return Path instead of String
    private MessageDatabase messageDatabase;

    public ChatView(ApiClient apiClient, Chat chat, Main app) {
        this.apiClient = apiClient;
        this.chat = chat;
        this.app = app;
        // Reset IV state when creating new chat view (handles reconnection)
        currentIV = null;
        // Initialize database
        this.messageDatabase = new MessageDatabase();
        createView();
        setupWebSocket();
        // Load messages from database
        loadMessagesFromDatabase();
    }

    public Chat getChat() {
        return chat;
    }

    private void setupWebSocket() {
        // Set up message callback when WebSocket client is available
        if (app.socket != null) {
            app.socket.setOnMessageReceived(this::onMessageReceived);
            // Clear the placeholder message once connected
            Platform.runLater(() -> {
                messageContainer.getChildren().clear();
                if (app.socket.isConnected()) {
                    addMessageToUI("System", "Chat connected. You can now send messages.", false, null);
                    // Reset IV when connection is established (handles reconnection)
                    resetIVOnConnection();
                } else {
                    addMessageToUI("System", "Connecting to chat...", false, null);
                }
            });
        } else {
            // If socket is not available yet, wait a bit and try again
            Platform.runLater(() -> {
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
                pause.setOnFinished(e -> setupWebSocket());
                pause.play();
            });
        }
    }

    /**
     * Resets IV when connection is established or key is available.
     * This ensures that on reconnection, we don't use stale IV state.
     */
    private void resetIVOnConnection() {
        // Reset IV to ensure fresh state on reconnection
        currentIV = null;
        ivInputField.clear();
        ivLabel.setText("");
        ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        
        // Monitor for key establishment and reset IV when key becomes available
        // This handles the case where key exchange happens after connection
        javafx.animation.PauseTransition checkKey = new javafx.animation.PauseTransition(javafx.util.Duration.millis(1000));
        checkKey.setOnFinished(e -> {
            if (app.socket != null && app.socket.getKey() != null) {
                // Key is now available, ensure IV is reset
                if (currentIV != null) {
                    currentIV = null;
                    ivInputField.clear();
                    ivLabel.setText("");
                }
            }
        });
        checkKey.play();
    }

    private void onMessageReceived(ChatMessage message) {
        Platform.runLater(() -> {
            String messageText = message.getMessage();
            
            // Handle system messages
            if ("SYSTEM".equals(message.getSender()) && "chat deleted".equals(messageText)) {
                // Chat was deleted, delete messages from database and switch to main view
                deleteChatMessages();
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Chat Deleted");
                alert.setHeaderText("Chat Deleted");
                alert.setContentText("This chat has been deleted. Returning to main screen.");
                alert.showAndWait();
                app.showMainView();
                return;
            }
            
            // Handle messages based on type field
            String messageType = message.getType();
            if (messageType == null) {
                // Backward compatibility: check prefixes if type is not set
                if (messageText.startsWith("FILE:")) {
                    messageType = "FILE";
                } else if (messageText.startsWith("ENCRYPTED:")) {
                    messageType = "ENCRYPTED";
                } else {
                    messageType = "TEXT";
                }
            }
            
            switch (messageType) {
                case "FILE":
                    // File link message - messageText contains just the fileId
                    addMessageToUI(message.getSender(), "[File Attachment]", false, messageText);
                    break;
                    
                case "ENCRYPTED":
                    try {
                        // Decrypt the message - messageText contains base64 encoded IV+encrypted data
                        byte[] combined = Base64.getDecoder().decode(messageText);
                        
                        // Get block size to determine IV size
                        byte[] key = app.socket.getKey();
                        if (key == null) {
                            addMessageToUI(message.getSender(), "[Encrypted - Key not available]", false, null);
                        } else {
                            dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                            int blockSize = blockCipher.blockSize();
                            
                            // Extract IV and encrypted data
                            byte[] iv = new byte[blockSize];
                            byte[] encrypted = new byte[combined.length - blockSize];
                            System.arraycopy(combined, 0, iv, 0, blockSize);
                            System.arraycopy(combined, blockSize, encrypted, 0, encrypted.length);
                            
                            // Create cipher and decrypt
                            SymmetricCipher cipher = EncryptionUtil.createCipher(
                                chat.getAlgorithm(),
                                chat.getMode(),
                                chat.getPadding(),
                                key,
                                iv
                            );
                            
                            byte[] decrypted = cipher.decrypt(encrypted);
                            String decryptedMessage = new String(decrypted, "UTF-8");
                            
                            // For backward compatibility, check if decrypted message has FILE: prefix
                            if (decryptedMessage.startsWith("FILE:")) {
                                String fileId = decryptedMessage.substring("FILE:".length()).trim();
                                addMessageToUI(message.getSender(), "[ENCRYPTED FILE]", true, fileId);
                            } else {
                                addMessageToUI(message.getSender(), "[ENCRYPTED] " + decryptedMessage, false, null);
                            }
                        }
                    } catch (Exception e) {
                        addMessageToUI(message.getSender(), "[Failed to decrypt: " + e.getMessage() + "]", false, null);
                    }
                    break;
                    
                case "ENCRYPTED_FILE":
                    // Encrypted file - messageText contains base64 encoded IV+encrypted data, decrypted data is fileId
                    try {
                        byte[] combined = Base64.getDecoder().decode(messageText);
                        
                        byte[] key = app.socket.getKey();
                        if (key == null) {
                            addMessageToUI(message.getSender(), "[Encrypted File - Key not available]", false, null);
                        } else {
                            dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                            int blockSize = blockCipher.blockSize();
                            
                            byte[] iv = new byte[blockSize];
                            byte[] encrypted = new byte[combined.length - blockSize];
                            System.arraycopy(combined, 0, iv, 0, blockSize);
                            System.arraycopy(combined, blockSize, encrypted, 0, encrypted.length);
                            
                            SymmetricCipher cipher = EncryptionUtil.createCipher(
                                chat.getAlgorithm(),
                                chat.getMode(),
                                chat.getPadding(),
                                key,
                                iv
                            );
                            
                            byte[] decrypted = cipher.decrypt(encrypted);
                            String fileId = new String(decrypted, "UTF-8");
                            addMessageToUI(message.getSender(), "[ENCRYPTED FILE]", true, fileId);
                        }
                    } catch (Exception e) {
                        addMessageToUI(message.getSender(), "[Failed to decrypt file: " + e.getMessage() + "]", false, null);
                    }
                    break;
                    
                case "TEXT":
                default:
                    // Regular unencrypted message
                    addMessageToUI(message.getSender(), messageText, false, null);
                    break;
            }
        });
    }

    private void createView() {
        // Top section with chat info and disconnect button
        HBox topBox = new HBox(10);
        topBox.setPadding(new Insets(10));
        topBox.setAlignment(Pos.CENTER_LEFT);

        chatInfoLabel = new Label();
        chatInfoLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        updateChatInfo();

        disconnectButton = new Button("Disconnect");
        disconnectButton.setOnAction(e -> handleDisconnect());

        topBox.getChildren().addAll(chatInfoLabel, new Separator(), disconnectButton);
        setTop(topBox);

        // Center section with message area
        messageContainer = new VBox(5);
        messageContainer.setPadding(new Insets(10));
        messageContainer.setStyle("-fx-background-color: white;");
        
        messageScrollPane = new ScrollPane(messageContainer);
        messageScrollPane.setFitToWidth(true);
        messageScrollPane.setFitToHeight(true);
        messageScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messageScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messageScrollPane.setStyle("-fx-background: white;");
        
        // Auto-scroll to bottom when content changes
        messageContainer.heightProperty().addListener((obs, oldVal, newVal) -> {
            messageScrollPane.setVvalue(1.0);
        });
        
        setCenter(messageScrollPane);

        // Bottom section with message input and buttons
        VBox bottomBox = new VBox(5);
        bottomBox.setPadding(new Insets(10));

        HBox inputBox = new HBox(10);
        inputBox.setAlignment(Pos.CENTER_LEFT);

        Label messageLabel = new Label("Message:");
        messageInput = new TextField();
        messageInput.setPrefWidth(400);
        messageInput.setOnAction(e -> handleSendMessage());

        sendButton = new Button("Send");
        sendButton.setOnAction(e -> handleSendMessage());

        encryptButton = new Button("Encrypt & Send");
        encryptButton.setOnAction(e -> handleEncryptAndSend());

        cancelEncryptButton = new Button("Cancel");
        cancelEncryptButton.setDisable(true);
        cancelEncryptButton.setOnAction(e -> handleCancelEncryption());

        attachFileButton = new Button("Attach File");
        attachFileButton.setOnAction(e -> handleAttachFile());

        encryptFileButton = new Button("Encrypt File");
        encryptFileButton.setDisable(true);
        encryptFileButton.setOnAction(e -> handleEncryptFile());

        sendFileButton = new Button("Send File");
        sendFileButton.setDisable(true);
        sendFileButton.setOnAction(e -> handleSendFile());

        cancelFileEncryptionButton = new Button("Cancel");
        cancelFileEncryptionButton.setDisable(true);
        cancelFileEncryptionButton.setOnAction(e -> handleCancelFileEncryption());

        encryptionProgress = new ProgressIndicator();
        encryptionProgress.setVisible(false);
        encryptionProgress.setPrefSize(20, 20);

        fileEncryptionProgress = new ProgressBar();
        fileEncryptionProgress.setVisible(false);
        fileEncryptionProgress.setPrefWidth(200);
        fileEncryptionProgress.setProgress(0);

        fileStatusLabel = new Label();
        fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        fileStatusLabel.setVisible(false);

        inputBox.getChildren().addAll(messageLabel, messageInput, sendButton, encryptButton, attachFileButton, 
            encryptFileButton, sendFileButton, cancelFileEncryptionButton, encryptionProgress);

        // IV management section
        HBox ivBox = new HBox(10);
        ivBox.setAlignment(Pos.CENTER_LEFT);
        ivBox.setPadding(new Insets(5, 0, 0, 0));

        Label ivLabelText = new Label("IV:");
        ivInputField = new TextField();
        ivInputField.setPrefWidth(300);
        ivInputField.setPromptText("Enter IV (hex or base64) or leave empty for random");
        ivInputField.textProperty().addListener((obs, oldVal, newVal) -> {
            updateIVFromInput();
        });

        generateIVButton = new Button("Generate Random");
        generateIVButton.setOnAction(e -> generateRandomIV());

        ivLabel = new Label();
        ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        ivBox.getChildren().addAll(ivLabelText, ivInputField, generateIVButton, ivLabel);

        // File encryption progress section
        HBox fileProgressBox = new HBox(10);
        fileProgressBox.setAlignment(Pos.CENTER_LEFT);
        fileProgressBox.setPadding(new Insets(5, 0, 0, 0));
        fileProgressBox.getChildren().addAll(fileEncryptionProgress, fileStatusLabel);

        bottomBox.getChildren().addAll(inputBox, ivBox, fileProgressBox);
        setBottom(bottomBox);

        // Add placeholder message
        addMessageToUI("System", "Chat connected. Messages will appear here.", false, null);
    }

    private void updateChatInfo() {
        String info = String.format("Chat with %s | Algorithm: %s | Mode: %s | Padding: %s",
                chat.getContactUsername(),
                chat.getAlgorithm(),
                chat.getMode(),
                chat.getPadding());
        chatInfoLabel.setText(info);
    }

    private void handleSendMessage() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // Send message via WebSocket
        if (app.socket != null && app.socket.isConnected()) {
            // Display sent message immediately
            addMessageToUI("You", message, false, null);
            
            // Send via WebSocket
            app.socket.sendMessage(message);
            messageInput.clear();
        } else {
            // Show error if not connected
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Not connected to chat. Please wait for connection...");
            alert.show();
        }
    }

    private void handleEncryptAndSend() {
        String message = messageInput.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // Check if key is available
        if (app.socket == null || app.socket.getKey() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Encryption key not available. Please wait for key exchange to complete.");
            alert.show();
            return;
        }

        // Check if already encrypting
        if (currentEncryptionTask != null && !currentEncryptionTask.isDone()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setContentText("Encryption already in progress. Please wait or cancel.");
            alert.show();
            return;
        }

        // Disable buttons and show progress
        encryptButton.setDisable(true);
        sendButton.setDisable(true);
        messageInput.setDisable(true);
        cancelEncryptButton.setDisable(false);
        encryptionProgress.setVisible(true);

        // Create encryption task
        currentEncryptionTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                updateMessage("Encrypting message...");
                
                byte[] key = app.socket.getKey();
                if (key == null) {
                    throw new IllegalStateException("Encryption key not available. Please wait for key exchange to complete.");
                }
                
                byte[] messageBytes = message.getBytes("UTF-8");
                
                // Create block cipher to determine block size for IV
                dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                int blockSize = blockCipher.blockSize();
                
                // Always generate a new IV for each encryption to avoid padding errors on reconnection
                // This ensures that each encryption uses a fresh IV, preventing issues when reconnecting
                byte[] iv = EncryptionUtil.generateIV(blockSize);
                
                // Update stored IV for display purposes
                final byte[] finalIV = iv.clone();
                Platform.runLater(() -> {
                    currentIV = finalIV;
                    updateIVDisplay();
                });
                
                // Create cipher
                SymmetricCipher cipher = EncryptionUtil.createCipher(
                    chat.getAlgorithm(),
                    chat.getMode(),
                    chat.getPadding(),
                    key,
                    iv
                );
                
                // Check for cancellation
                if (isCancelled()) {
                    return null;
                }
                
                // Encrypt
                byte[] encrypted = cipher.encrypt(messageBytes);
                
                // Check for cancellation again
                if (isCancelled()) {
                    return null;
                }
                
                // Combine IV and encrypted data, then encode to Base64
                byte[] combined = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
                
                String base64Encrypted = Base64.getEncoder().encodeToString(combined);
                
                // Return just the base64 data, type will be set when sending
                return base64Encrypted;
            }
        };

        // Handle task completion
        currentEncryptionTask.setOnSucceeded(e -> {
            String encryptedMessage = currentEncryptionTask.getValue();
            if (encryptedMessage != null) {
                // Display encrypted message indicator
                addMessageToUI("You", "[ENCRYPTED] " + message, false, null);
                
                // Send encrypted message with type "ENCRYPTED"
                if (app.socket != null && app.socket.isConnected()) {
                    app.socket.sendMessage(encryptedMessage, "ENCRYPTED");
                    messageInput.clear();
                }
            }
            
            // Reset UI
            resetEncryptionUI();
        });

        currentEncryptionTask.setOnFailed(e -> {
            Throwable exception = currentEncryptionTask.getException();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Encryption failed: " + exception.getMessage());
                alert.show();
                resetEncryptionUI();
            });
        });

        currentEncryptionTask.setOnCancelled(e -> {
            Platform.runLater(() -> {
                addMessageToUI("System", "Encryption cancelled.", false, null);
                resetEncryptionUI();
            });
        });

        // Start encryption in background thread
        new Thread(currentEncryptionTask).start();
    }

    private void handleCancelEncryption() {
        if (currentEncryptionTask != null && !currentEncryptionTask.isDone()) {
            currentEncryptionTask.cancel();
        }
    }

    private void resetEncryptionUI() {
        encryptButton.setDisable(false);
        sendButton.setDisable(false);
        messageInput.setDisable(false);
        cancelEncryptButton.setDisable(true);
        encryptionProgress.setVisible(false);
        currentEncryptionTask = null;
    }

    private void generateRandomIV() {
        if (app.socket == null || app.socket.getKey() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Key not available. Cannot determine IV size.");
            alert.show();
            return;
        }

        try {
            byte[] key = app.socket.getKey();
            dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
            int blockSize = blockCipher.blockSize();
            
            currentIV = EncryptionUtil.generateIV(blockSize);
            updateIVDisplay();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Failed to generate IV: " + e.getMessage());
            alert.show();
        }
    }

    private void updateIVFromInput() {
        String ivText = ivInputField.getText().trim();
        if (ivText.isEmpty()) {
            currentIV = null;
            ivLabel.setText("");
            return;
        }

        try {
            byte[] iv;
            // Try to parse as hex first (must be even number of hex digits)
            if (ivText.matches("[0-9A-Fa-f]+") && ivText.length() % 2 == 0) {
                HexFormat hexFormat = HexFormat.of();
                iv = hexFormat.parseHex(ivText);
            } else {
                // Try to parse as base64
                iv = Base64.getDecoder().decode(ivText);
            }

            // Validate IV size if key is available
            if (app.socket != null && app.socket.getKey() != null) {
                byte[] key = app.socket.getKey();
                dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                int blockSize = blockCipher.blockSize();
                
                if (iv.length != blockSize) {
                    ivLabel.setText("Invalid size: " + iv.length + " bytes (expected " + blockSize + ")");
                    ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                    currentIV = null;
                    return;
                }
            } else {
                // Key not available yet, just store the IV (will be validated during encryption)
                ivLabel.setText("IV set: " + iv.length + " bytes (will validate on encryption)");
                ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: orange;");
            }

            currentIV = iv;
            if (app.socket != null && app.socket.getKey() != null) {
                updateIVDisplay();
            }
        } catch (Exception e) {
            ivLabel.setText("Invalid format: " + e.getMessage());
            ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            currentIV = null;
        }
    }

    private void updateIVDisplay() {
        if (currentIV == null) {
            ivLabel.setText("");
            ivInputField.clear();
            return;
        }

        // Display IV in hex format
        HexFormat hexFormat = HexFormat.of().withUpperCase();
        String hexIV = hexFormat.formatHex(currentIV);
        ivLabel.setText("Current IV: " + hexIV);
        ivLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
        
        // Update input field with hex representation
        ivInputField.setText(hexIV);
    }

    private void addMessageToUI(String sender, String messageText, boolean isEncryptedFile, String fileId) {
        // Save message to database (don't save system messages)
        if (!"System".equals(sender)) {
            saveMessageToDatabase(sender, messageText, isEncryptedFile, fileId);
        }
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 10, 5, 10));
        messageBox.setAlignment(Pos.CENTER_LEFT);
        
        Label senderLabel = new Label(sender + ":");
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        if (fileId != null) {
            // File attachment message with download button
            Label fileLabel = new Label(isEncryptedFile ? "[ENCRYPTED FILE]" : "[File Attachment]");
            fileLabel.setStyle("-fx-text-fill: #666;");
            
            Button downloadButton = new Button("⬇ Download");
            downloadButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: bold; " +
                "-fx-padding: 8 15 8 15; " +
                "-fx-background-radius: 5; " +
                "-fx-cursor: hand;"
            );
            
            // Hover effect
            downloadButton.setOnMouseEntered(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #45a049; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);"
                );
            });
            
            downloadButton.setOnMouseExited(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #4CAF50; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand;"
                );
            });
            
            // Click effect
            downloadButton.setOnMousePressed(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #3d8b40; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 3, 0, 0, 1);"
                );
            });
            
            downloadButton.setOnMouseReleased(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #45a049; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);"
                );
            });
            
            downloadButton.setOnAction(e -> handleFileDownload(fileId));
            
            Label fileIdLabel = new Label("(" + fileId.substring(0, 8) + "...)");
            fileIdLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");
            
            messageBox.getChildren().addAll(senderLabel, fileLabel, downloadButton, fileIdLabel);
        } else {
            // Regular text message
            Text messageLabel = new Text(messageText);
            messageLabel.setWrappingWidth(600);
            messageLabel.setStyle("-fx-font-family: monospace; -fx-fill: #333;");
            
            messageBox.getChildren().addAll(senderLabel, messageLabel);
        }
        
        messageContainer.getChildren().add(messageBox);
        
        // Auto-scroll to bottom
        Platform.runLater(() -> {
            messageScrollPane.setVvalue(1.0);
        });
    }

    private void handleDisconnect() {
        apiClient.disconnectFromChat(chat.getId())
                .thenRun(() -> {
                    javafx.application.Platform.runLater(() -> {
                        app.showMainView();
                    });
                })
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setContentText("Failed to disconnect from chat: " + ex.getCause().getMessage());
                        alert.show();
                    });
                    return null;
                });
    }

    private void handleAttachFile() {
        // Check if key is available
        if (app.socket == null || app.socket.getKey() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Encryption key not available. Please wait for key exchange to complete.");
            alert.show();
            return;
        }

        // Open file chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Attach");
        Stage stage = (Stage) getScene().getWindow();
        File file = fileChooser.showOpenDialog(stage);

        if (file == null) {
            return; // User cancelled
        }

        // Store selected file
        selectedFile = file;
        
        // Update UI
        encryptFileButton.setDisable(false);
        fileStatusLabel.setText("File selected: " + file.getName() + " (" + formatFileSize(file.length()) + ")");
        fileStatusLabel.setVisible(true);
        fileEncryptionProgress.setVisible(false);
        fileEncryptionProgress.setProgress(0);
        cancelFileEncryptionButton.setDisable(true);
    }

    private void handleEncryptFile() {
        if (selectedFile == null) {
            return;
        }

        // Check if key is available
        if (app.socket == null || app.socket.getKey() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Encryption key not available. Please wait for key exchange to complete.");
            alert.show();
            return;
        }

        // Check if already encrypting
        if (currentFileEncryptionTask != null && !currentFileEncryptionTask.isDone()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setContentText("File encryption already in progress. Please wait or cancel.");
            alert.show();
            return;
        }

        // Disable buttons and show progress
        attachFileButton.setDisable(true);
        encryptFileButton.setDisable(true);
        cancelFileEncryptionButton.setDisable(false);
        fileEncryptionProgress.setVisible(true);
        fileEncryptionProgress.setProgress(-1); // Indeterminate progress
        fileStatusLabel.setText("Encrypting file...");

        // Create task for file encryption only (no upload)
        currentFileEncryptionTask = new Task<Path>() {
            @Override
            protected Path call() throws Exception {
                updateProgress(-1, 1); // Indeterminate
                updateMessage("Encrypting file...");

                byte[] key = app.socket.getKey();
                dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                int blockSize = blockCipher.blockSize();

                // Generate IV for file encryption
                byte[] iv = EncryptionUtil.generateIV(blockSize);

                // Create temporary encrypted file
                Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
                Path tempEncryptedFile = tempDir.resolve("encrypted_" + System.currentTimeMillis() + "_" + selectedFile.getName());

                // Encrypt file
                SymmetricCipher cipher = EncryptionUtil.createCipher(
                    chat.getAlgorithm(),
                    chat.getMode(),
                    chat.getPadding(),
                    key,
                    iv
                );

                updateMessage("Encrypting file...");
                
                // Check for cancellation
                if (isCancelled()) {
                    return null;
                }
                
                cipher.encryptFile(selectedFile.toPath(), tempEncryptedFile);

                // Check for cancellation again
                if (isCancelled()) {
                    Files.deleteIfExists(tempEncryptedFile);
                    return null;
                }

                // Prepend IV to encrypted file (like we do with messages)
                updateMessage("Preparing file...");
                Path encryptedFile = tempDir.resolve("encrypted_with_iv_" + System.currentTimeMillis() + "_" + selectedFile.getName());
                try (FileOutputStream fos = new FileOutputStream(encryptedFile.toFile());
                     FileInputStream fis = new FileInputStream(tempEncryptedFile.toFile())) {
                    // Write IV first
                    fos.write(iv);
                    // Then write encrypted data
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        if (isCancelled()) {
                            Files.deleteIfExists(tempEncryptedFile);
                            Files.deleteIfExists(encryptedFile);
                            return null;
                        }
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                Files.deleteIfExists(tempEncryptedFile);

                updateProgress(1.0, 1.0); // 100% progress
                return encryptedFile; // Return path to encrypted file, don't upload yet
            }
        };

        // Bind progress bar to task progress
        fileEncryptionProgress.progressProperty().bind(currentFileEncryptionTask.progressProperty());

        currentFileEncryptionTask.setOnSucceeded(e -> {
            Path encryptedFile = currentFileEncryptionTask.getValue();
            if (encryptedFile != null) {
                // Store encrypted file path for later sending
                encryptedFileWithIV = encryptedFile;
                fileStatusLabel.setText("File encrypted successfully! Click 'Send File' to upload and send.");
                fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                // Enable send button
                sendFileButton.setDisable(false);
                encryptFileButton.setDisable(true);
                cancelFileEncryptionButton.setDisable(true);
            }
            // Don't reset UI yet - wait for user to click Send
            fileEncryptionProgress.setVisible(false);
            fileEncryptionProgress.progressProperty().unbind();
            fileEncryptionProgress.setProgress(0);
            currentFileEncryptionTask = null;
        });

        currentFileEncryptionTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("File encryption/upload failed: " + currentFileEncryptionTask.getException().getMessage());
                alert.show();
                fileStatusLabel.setText("Encryption failed: " + currentFileEncryptionTask.getException().getMessage());
                fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                resetFileEncryptionUI();
            });
        });

        currentFileEncryptionTask.setOnCancelled(e -> {
            Platform.runLater(() -> {
                // Clean up any partial encrypted files
                if (encryptedFileWithIV != null) {
                    try {
                        Files.deleteIfExists(encryptedFileWithIV);
                    } catch (Exception ex) {
                        // Ignore cleanup errors
                    }
                    encryptedFileWithIV = null;
                }
                fileStatusLabel.setText("Encryption cancelled.");
                fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: orange;");
                resetFileEncryptionUI();
            });
        });

        // Start encryption in background thread
        new Thread(currentFileEncryptionTask).start();
    }

    private void handleCancelFileEncryption() {
        if (currentFileEncryptionTask != null && !currentFileEncryptionTask.isDone()) {
            currentFileEncryptionTask.cancel();
        }
    }

    private void handleSendFile() {
        if (encryptedFileWithIV == null || !Files.exists(encryptedFileWithIV)) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("No encrypted file available. Please encrypt a file first.");
            alert.show();
            return;
        }

        // Disable send button and show progress
        sendFileButton.setDisable(true);
        fileEncryptionProgress.setVisible(true);
        fileEncryptionProgress.setProgress(-1); // Indeterminate
        fileStatusLabel.setText("Uploading file...");

        // Create task for file upload
        Task<String> uploadTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                updateProgress(-1, 1);
                updateMessage("Uploading file...");

                // Upload encrypted file
                FileUploadResponse uploadResponse = apiClient.uploadFile(encryptedFileWithIV.toFile()).get();
                
                // Clean up encrypted file after successful upload
                Files.deleteIfExists(encryptedFileWithIV);

                updateProgress(1.0, 1.0);
                return uploadResponse.getFileId();
            }
        };

        // Bind progress bar to task progress
        fileEncryptionProgress.progressProperty().bind(uploadTask.progressProperty());

        uploadTask.setOnSucceeded(e -> {
            String fileId = uploadTask.getValue();
            if (fileId != null) {
                // Send file link via WebSocket with type "FILE"
                if (app.socket != null && app.socket.isConnected()) {
                    app.socket.sendMessage(fileId, "FILE");
                    addMessageToUI("You", "[File Attachment]", false, fileId);
                }
                fileStatusLabel.setText("File uploaded and sent successfully!");
                fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
            }
            resetFileEncryptionUI();
        });

        uploadTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("File upload failed: " + uploadTask.getException().getMessage());
                alert.show();
                fileStatusLabel.setText("Upload failed: " + uploadTask.getException().getMessage());
                fileStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                sendFileButton.setDisable(false); // Re-enable send button to retry
                fileEncryptionProgress.setVisible(false);
                fileEncryptionProgress.progressProperty().unbind();
                fileEncryptionProgress.setProgress(0);
            });
        });

        // Start upload in background thread
        new Thread(uploadTask).start();
    }

    private void resetFileEncryptionUI() {
        attachFileButton.setDisable(false);
        encryptFileButton.setDisable(true);
        sendFileButton.setDisable(true);
        cancelFileEncryptionButton.setDisable(true);
        fileEncryptionProgress.setVisible(false);
        fileEncryptionProgress.progressProperty().unbind();
        fileEncryptionProgress.setProgress(0);
        fileStatusLabel.setVisible(false);
        selectedFile = null;
        encryptedFileWithIV = null;
        currentFileEncryptionTask = null;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private void handleFileDownload(String fileId) {
        if (app.socket == null || app.socket.getKey() == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setContentText("Encryption key not available. Cannot decrypt file.");
            alert.show();
            return;
        }

        // Show progress
        encryptionProgress.setVisible(true);

        Task<Void> downloadTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Downloading file...");

                // Get file info first
                FileInfo fileInfo = apiClient.getFileInfo(fileId).get();

                // Download encrypted file to temp location
                Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
                Path encryptedFileWithIV = tempDir.resolve("encrypted_with_iv_" + fileId);
                File tempEncryptedFile = encryptedFileWithIV.toFile();

                updateMessage("Downloading file...");
                apiClient.downloadFile(fileId, tempEncryptedFile).get();

                // Extract IV from the beginning of the file
                byte[] key = app.socket.getKey();
                dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                int blockSize = blockCipher.blockSize();

                byte[] iv = new byte[blockSize];
                try (FileInputStream fis = new FileInputStream(tempEncryptedFile)) {
                    int bytesRead = fis.read(iv);
                    if (bytesRead != blockSize) {
                        throw new IOException("Failed to read IV from encrypted file");
                    }
                }

                // Create file without IV for decryption
                Path encryptedFile = tempDir.resolve("encrypted_" + fileId);
                try (FileInputStream fis = new FileInputStream(tempEncryptedFile);
                     FileOutputStream fos = new FileOutputStream(encryptedFile.toFile())) {
                    // Skip IV
                    fis.skip(blockSize);
                    // Copy rest of the file
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                Files.deleteIfExists(encryptedFileWithIV);

                // Decrypt file
                updateMessage("Decrypting file...");
                SymmetricCipher cipher = EncryptionUtil.createCipher(
                    chat.getAlgorithm(),
                    chat.getMode(),
                    chat.getPadding(),
                    key,
                    iv
                );

                // Choose save location on JavaFX thread
                final FileInfo finalFileInfo = fileInfo;
                final Path finalEncryptedFile = encryptedFile;
                Platform.runLater(() -> {
                    FileChooser fileChooser = new FileChooser();
                    fileChooser.setTitle("Save Decrypted File");
                    fileChooser.setInitialFileName(finalFileInfo.getFileName());
                    Stage stage = (Stage) getScene().getWindow();
                    File saveFile = fileChooser.showSaveDialog(stage);

                    if (saveFile != null) {
                        try {
                            cipher.decryptFile(finalEncryptedFile, saveFile.toPath());
                            Files.deleteIfExists(finalEncryptedFile);
                            
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setContentText("File decrypted and saved to: " + saveFile.getAbsolutePath());
                            alert.show();
                        } catch (Exception ex) {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Failed to decrypt file: " + ex.getMessage());
                            alert.show();
                        }
                    } else {
                        // User cancelled, clean up
                        try {
                            Files.deleteIfExists(finalEncryptedFile);
                        } catch (Exception ex) {
                            // Ignore cleanup errors
                        }
                    }
                    encryptionProgress.setVisible(false);
                });

                return null;
            }
        };

        downloadTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("File download failed: " + downloadTask.getException().getMessage());
                alert.show();
                encryptionProgress.setVisible(false);
            });
        });

        new Thread(downloadTask).start();
    }

    /**
     * Save a message to the local database.
     */
    private void saveMessageToDatabase(String sender, String messageText, boolean isEncryptedFile, String fileId) {
        try {
            // Determine message type and prepare message content
            String type;
            String messageContent = messageText;
            
            if (fileId != null) {
                type = isEncryptedFile ? "ENCRYPTED_FILE" : "FILE";
            } else if (messageText.startsWith("[ENCRYPTED]")) {
                type = "ENCRYPTED";
                // Store the actual encrypted content (remove the "[ENCRYPTED] " prefix if present)
                if (messageText.startsWith("[ENCRYPTED] ")) {
                    messageContent = messageText.substring("[ENCRYPTED] ".length());
                }
            } else {
                type = "TEXT";
            }
            
            LocalMessage localMessage = LocalMessage.builder()
                    .chatId(chat.getId())
                    .sender(sender)
                    .receiver(chat.getContactUsername())
                    .message(messageContent)
                    .type(type)
                    .timestamp(java.time.LocalDateTime.now())
                    .fileId(fileId)
                    .build();
            
            messageDatabase.saveMessage(localMessage);
        } catch (Exception e) {
            System.err.println("Failed to save message to database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load messages from the local database and display them.
     */
    private void loadMessagesFromDatabase() {
        try {
            List<LocalMessage> messages = messageDatabase.loadMessages(chat.getId());
            Platform.runLater(() -> {
                for (LocalMessage msg : messages) {
                    // Determine if it's an encrypted file based on type
                    boolean isEncryptedFile = "ENCRYPTED_FILE".equals(msg.getType());
                    String displayText = msg.getMessage();
                    
                    // Add prefix for encrypted messages if needed
                    if ("ENCRYPTED".equals(msg.getType()) && !displayText.startsWith("[ENCRYPTED]")) {
                        displayText = "[ENCRYPTED] " + displayText;
                    }
                    
                    // Display message without saving to database (it's already in the database)
                    displayMessageInUI(msg.getSender(), displayText, isEncryptedFile, msg.getFileId());
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to load messages from database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Display a message in the UI without saving to database.
     * Used when loading messages from database.
     */
    private void displayMessageInUI(String sender, String messageText, boolean isEncryptedFile, String fileId) {
        // This is the same as addMessageToUI but without the database save
        // Copy the UI code from addMessageToUI
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 10, 5, 10));
        messageBox.setAlignment(Pos.CENTER_LEFT);
        
        Label senderLabel = new Label(sender + ":");
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        if (fileId != null) {
            // File attachment message with download button
            Label fileLabel = new Label(isEncryptedFile ? "[ENCRYPTED FILE]" : "[File Attachment]");
            fileLabel.setStyle("-fx-text-fill: #666;");
            
            Button downloadButton = new Button("⬇ Download");
            downloadButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: bold; " +
                "-fx-padding: 8 15 8 15; " +
                "-fx-background-radius: 5; " +
                "-fx-cursor: hand;"
            );
            
            downloadButton.setOnMouseEntered(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #45a049; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);"
                );
            });
            
            downloadButton.setOnMouseExited(e -> {
                downloadButton.setStyle(
                    "-fx-background-color: #4CAF50; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: bold; " +
                    "-fx-padding: 8 15 8 15; " +
                    "-fx-background-radius: 5; " +
                    "-fx-cursor: hand;"
                );
            });
            
            downloadButton.setOnAction(e -> handleFileDownload(fileId));
            
            messageBox.getChildren().addAll(senderLabel, fileLabel, downloadButton);
        } else {
            // Regular text message
            Text messageTextNode = new Text(messageText);
            messageTextNode.setWrappingWidth(600);
            messageBox.getChildren().addAll(senderLabel, messageTextNode);
        }
        
        messageContainer.getChildren().add(messageBox);
    }

    /**
     * Delete all messages for this chat from the database.
     * Called when chat is deleted.
     */
    public void deleteChatMessages() {
        try {
            messageDatabase.deleteMessagesByChatId(chat.getId());
        } catch (Exception e) {
            System.err.println("Failed to delete messages from database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

