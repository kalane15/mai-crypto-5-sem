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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import java.util.concurrent.CompletableFuture;

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
    private String originalFileName; // Store original file name before encryption
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
            if ("System".equals(message.getSender()) && "chat deleted".equals(messageText)) {
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
                case "SYSTEM":
                    // System message - display with System sender
                    addMessageToUI("System", messageText, false, null);
                    break;
                    
                case "FILE":
                    // File link message - messageText contains just the fileId
                    // Get file info first to check if it's an image, then download to local storage
                    String receivedFileId = messageText;
                    apiClient.getFileInfo(receivedFileId).thenAccept(fileInfo -> {
                        String fileName = fileInfo != null ? fileInfo.getFileName() : null;
                        // Download file to local storage
                        downloadFileToLocalStorage(receivedFileId, fileName).thenAccept(localPath -> {
                            Platform.runLater(() -> {
                                addMessageToUI(message.getSender(), "", true, receivedFileId, localPath);
                            });
                        }).exceptionally(ex -> {
                            System.err.println("Failed to download received file to local storage: " + ex.getMessage());
                            ex.printStackTrace();
                            // Still add message to UI even if download fails
                            Platform.runLater(() -> {
                                addMessageToUI(message.getSender(), "", true, receivedFileId, null);
                            });
                            return null;
                        });
                    }).exceptionally(ex -> {
                        System.err.println("Failed to get file info: " + ex.getMessage());
                        ex.printStackTrace();
                        // Download without file info
                        downloadFileToLocalStorage(receivedFileId, null).thenAccept(localPath -> {
                            Platform.runLater(() -> {
                                addMessageToUI(message.getSender(), "", true, receivedFileId, localPath);
                            });
                        }).exceptionally(ex2 -> {
                            Platform.runLater(() -> {
                                addMessageToUI(message.getSender(), "", true, receivedFileId, null);
                            });
                            return null;
                        });
                        return null;
                    });
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
                            
                            // Get file info first to check if it's an image, then download to local storage
                            String encryptedFileId = fileId;
                            apiClient.getFileInfo(encryptedFileId).thenAccept(fileInfo -> {
                                String fileName = fileInfo != null ? fileInfo.getFileName() : null;
                                // Download file to local storage
                                downloadFileToLocalStorage(encryptedFileId, fileName).thenAccept(localPath -> {
                                    Platform.runLater(() -> {
                                        addMessageToUI(message.getSender(), "", true, encryptedFileId, localPath);
                                    });
                                }).exceptionally(ex -> {
                                    System.err.println("Failed to download encrypted file to local storage: " + ex.getMessage());
                                    ex.printStackTrace();
                                    // Still add message to UI even if download fails
                                    Platform.runLater(() -> {
                                        addMessageToUI(message.getSender(), "", true, encryptedFileId, null);
                                    });
                                    return null;
                                });
                            }).exceptionally(ex -> {
                                System.err.println("Failed to get file info: " + ex.getMessage());
                                ex.printStackTrace();
                                // Download without file info
                                downloadFileToLocalStorage(encryptedFileId, null).thenAccept(localPath -> {
                                    Platform.runLater(() -> {
                                        addMessageToUI(message.getSender(), "", true, encryptedFileId, localPath);
                                    });
                                }).exceptionally(ex2 -> {
                                    Platform.runLater(() -> {
                                        addMessageToUI(message.getSender(), "", true, encryptedFileId, null);
                                    });
                                    return null;
                                });
                                return null;
                            });
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
        addMessageToUI(sender, messageText, isEncryptedFile, fileId, null);
    }
    
    private void addMessageToUI(String sender, String messageText, boolean isEncryptedFile, String fileId, String localFilePath) {
        // Save message to database (don't save system messages)
        if (!"System".equals(sender)) {
            saveMessageToDatabase(sender, messageText, isEncryptedFile, fileId, localFilePath);
        }
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 10, 5, 10));
        messageBox.setAlignment(Pos.CENTER_LEFT);
        
        Label senderLabel = new Label(sender + ":");
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        if (fileId != null && !fileId.isEmpty()) {
            // If we have a local file path, use it directly
            if (localFilePath != null && !localFilePath.isEmpty() && Files.exists(Paths.get(localFilePath))) {
                // File is already downloaded locally - check if it's an image
                File localFile = new File(localFilePath);
                String fileName = localFile.getName();
                if (isImageFile(fileName)) {
                    // Display image from local file
                    displayImageFromLocalFile(messageBox, senderLabel, localFilePath);
                } else {
                    // Display file attachment with local path
                    displayFileAttachmentFromLocal(messageBox, senderLabel, localFilePath);
                }
            } else {
                // File not downloaded yet, check if it's an image and download if needed
                System.out.println("Checking file for image display: fileId=" + fileId);
                apiClient.getFileInfo(fileId).thenAccept(fileInfo -> {
                    Platform.runLater(() -> {
                        String fileName = fileInfo != null ? fileInfo.getFileName() : null;
                        System.out.println("File info received: fileName=" + fileName + ", fileId=" + fileId);
                        if (fileName != null && isImageFile(fileName)) {
                            System.out.println("File is an image, displaying inline: " + fileName);
                            // Display image inline
                            displayImageInMessage(messageBox, senderLabel, fileId, isEncryptedFile, fileInfo);
                        } else {
                            System.out.println("File is not an image or fileName is null, showing as attachment: " + fileName);
                            // Display file attachment with download button
                            displayFileAttachment(messageBox, senderLabel, fileId, isEncryptedFile);
                        }
                    });
                }).exceptionally(ex -> {
                    // If we can't get file info, show as regular file attachment
                    System.err.println("Failed to get file info for fileId=" + fileId + ": " + ex.getMessage());
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        displayFileAttachment(messageBox, senderLabel, fileId, isEncryptedFile);
                    });
                    return null;
                });
            }
        } else {
            // Regular text message
            Text messageLabel = new Text(messageText);
            messageLabel.setWrappingWidth(600);
            messageLabel.setStyle("-fx-font-family: monospace; -fx-fill: #333;");
            
            messageBox.getChildren().addAll(senderLabel, messageLabel);
            messageContainer.getChildren().add(messageBox);
        }
        
        // Auto-scroll to bottom
        Platform.runLater(() -> {
            messageScrollPane.setVvalue(1.0);
        });
    }
    
    /**
     * Checks if a file is an image based on its extension.
     */
    private boolean isImageFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            System.out.println("isImageFile: fileName is null or empty");
            return false;
        }
        String lowerName = fileName.toLowerCase().trim();
        boolean isImage = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
               lowerName.endsWith(".png") || lowerName.endsWith(".gif") ||
               lowerName.endsWith(".bmp") || lowerName.endsWith(".webp") ||
               lowerName.endsWith(".svg");
        System.out.println("isImageFile check: fileName=" + fileName + ", lowerName=" + lowerName + ", isImage=" + isImage);
        return isImage;
    }
    
    /**
     * Displays an image inline in the message.
     */
    private void displayImageInMessage(HBox messageBox, Label senderLabel, String fileId, 
                                      boolean isEncryptedFile, FileInfo fileInfo) {
        VBox imageContainer = new VBox(5);
        imageContainer.setPadding(new Insets(5));
        
        // Add sender label
        imageContainer.getChildren().add(senderLabel);
        
        // Create placeholder while loading
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(30, 30);
        Label loadingLabel = new Label("Loading image...");
        loadingLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        VBox loadingBox = new VBox(5, loadingIndicator, loadingLabel);
        loadingBox.setAlignment(Pos.CENTER);
        loadingBox.setPadding(new Insets(10));
        imageContainer.getChildren().add(loadingBox);
        
        // Add to message container first
        messageBox.getChildren().add(imageContainer);
        messageContainer.getChildren().add(messageBox);
        
        // Download and decrypt image in background
        Task<Image> imageLoadTask = new Task<Image>() {
            @Override
            protected Image call() throws Exception {
                updateMessage("Downloading image...");
                
                // Files from server are always encrypted, so we need to decrypt them
                // Files from DB are marked as unencrypted, but they're still encrypted on server
                // So we always try to decrypt if key is available
                boolean needsDecryption = (app.socket != null && app.socket.getKey() != null);
                
                if (needsDecryption && (app.socket == null || app.socket.getKey() == null)) {
                    throw new IllegalStateException("Encryption key not available");
                }
                
                // Download file
                Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
                Path tempFile = tempDir.resolve("image_" + fileId + "_" + System.currentTimeMillis());
                
                if (needsDecryption) {
                    // File is encrypted on server (either explicitly marked or from DB)
                    // Download and decrypt
                    Path encryptedFileWithIV = tempDir.resolve("encrypted_with_iv_" + fileId);
                    File tempEncryptedFile = encryptedFileWithIV.toFile();
                    apiClient.downloadFile(fileId, tempEncryptedFile).get();
                    
                    // Extract IV and decrypt
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
                        fis.skip(blockSize);
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    Files.deleteIfExists(encryptedFileWithIV);
                    
                    // Decrypt file
                    updateMessage("Decrypting image...");
                    SymmetricCipher cipher = EncryptionUtil.createCipher(
                        chat.getAlgorithm(),
                        chat.getMode(),
                        chat.getPadding(),
                        key,
                        iv
                    );
                    
                    cipher.decryptFile(encryptedFile, tempFile);
                    Files.deleteIfExists(encryptedFile);
                } else {
                    // No key available - try to download as unencrypted (shouldn't happen for files from server)
                    File tempFileObj = tempFile.toFile();
                    apiClient.downloadFile(fileId, tempFileObj).get();
                }
                
                // Load image from file
                updateMessage("Loading image...");
                Image image = new Image("file:" + tempFile.toAbsolutePath().toString());
                
                // Clean up temp file after a delay (let image load first)
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        Files.deleteIfExists(tempFile);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }).start();
                
                return image;
            }
        };
        
        imageLoadTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                try {
                    Image image = imageLoadTask.getValue();
                    if (image != null && !image.isError()) {
                        // Remove loading indicator
                        imageContainer.getChildren().clear();
                        imageContainer.getChildren().add(senderLabel);
                        
                        // Create ImageView with constraints
                        ImageView imageView = new ImageView(image);
                        // Set max dimensions (preserve aspect ratio)
                        double maxWidth = 400;
                        double maxHeight = 400;
                        
                        if (image.getWidth() > maxWidth || image.getHeight() > maxHeight) {
                            if (image.getWidth() > image.getHeight()) {
                                imageView.setFitWidth(maxWidth);
                            } else {
                                imageView.setFitHeight(maxHeight);
                            }
                            imageView.setPreserveRatio(true);
                        }
                        
                        imageView.setSmooth(true);
                        imageView.setCache(true);
                        
                        // Add download button as well
                        Button downloadButton = createDownloadButton(fileId);
                        HBox imageBox = new HBox(10);
                        imageBox.setAlignment(Pos.CENTER_LEFT);
                        imageBox.getChildren().addAll(imageView, downloadButton);
                        
                        imageContainer.getChildren().add(imageBox);
                        
                        // Add file name label
                        Label fileNameLabel = new Label(fileInfo.getFileName());
                        fileNameLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");
                        imageContainer.getChildren().add(fileNameLabel);
                    } else {
                        // Image failed to load, show error
                        imageContainer.getChildren().clear();
                        imageContainer.getChildren().add(senderLabel);
                        Label errorLabel = new Label("Failed to load image");
                        errorLabel.setStyle("-fx-text-fill: red;");
                        imageContainer.getChildren().add(errorLabel);
                        displayFileAttachment(messageBox, senderLabel, fileId, isEncryptedFile);
                    }
                } catch (Exception ex) {
                    // Error loading image, fall back to file attachment
                    imageContainer.getChildren().clear();
                    displayFileAttachment(messageBox, senderLabel, fileId, isEncryptedFile);
                }
            });
        });
        
        imageLoadTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                // Remove loading indicator and show file attachment instead
                imageContainer.getChildren().clear();
                displayFileAttachment(messageBox, senderLabel, fileId, isEncryptedFile);
            });
        });
        
        new Thread(imageLoadTask).start();
    }
    
    /**
     * Displays a file attachment with download button (non-image files).
     */
    private void displayFileAttachment(HBox messageBox, Label senderLabel, String fileId, boolean isEncryptedFile) {
        // Clear message box first
        messageBox.getChildren().clear();
        messageBox.getChildren().add(senderLabel);
        
        // File attachment message with download button
        Label fileLabel = new Label(isEncryptedFile ? "[ENCRYPTED FILE]" : "[File Attachment]");
        fileLabel.setStyle("-fx-text-fill: #666;");
        
        Button downloadButton = createDownloadButton(fileId);
        
        Label fileIdLabel = new Label("(" + fileId.substring(0, Math.min(8, fileId.length())) + "...)");
        fileIdLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");
        
        messageBox.getChildren().addAll(fileLabel, downloadButton, fileIdLabel);
        
        // Make sure message box is in container
        if (!messageContainer.getChildren().contains(messageBox)) {
            messageContainer.getChildren().add(messageBox);
        }
    }
    
    /**
     * Creates a styled download button.
     */
    private Button createDownloadButton(String fileId) {
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
        return downloadButton;
    }

    private void handleDisconnect() {
        // Disconnect socket first
        app.manageDisconnect();
        
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

        // Store selected file and original name
        selectedFile = file;
        originalFileName = file.getName(); // Save original filename
        
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

                // Upload encrypted file with original filename
                FileUploadResponse uploadResponse = apiClient.uploadFile(
                    encryptedFileWithIV.toFile(), 
                    originalFileName != null ? originalFileName : encryptedFileWithIV.getFileName().toString()
                ).get();
                
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
                    // Files are always encrypted before sending, so isEncryptedFile = true
                    // Download file to local storage after sending
                    downloadFileToLocalStorage(fileId, originalFileName).thenAccept(localPath -> {
                        Platform.runLater(() -> {
                            addMessageToUI("You", "", true, fileId, localPath);
                        });
                    }).exceptionally(ex -> {
                        System.err.println("Failed to download file to local storage: " + ex.getMessage());
                        ex.printStackTrace();
                        // Still add message to UI even if download fails
                        Platform.runLater(() -> {
                            addMessageToUI("You", "", true, fileId, null);
                        });
                        return null;
                    });
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
        originalFileName = null;
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
        // Check if file is encrypted - we need to get file info first
        // For now, we'll assume encrypted if key is available (backward compatibility)
        // In practice, we should check the message type, but for download button we'll try
        boolean needsDecryption = app.socket != null && app.socket.getKey() != null;
        
        if (!needsDecryption) {
            // Try to download as unencrypted file
            Task<Void> downloadTask = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    updateMessage("Downloading file...");
                    FileInfo fileInfo = apiClient.getFileInfo(fileId).get();
                    Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
                    File tempFile = tempDir.resolve("file_" + fileId + "_" + System.currentTimeMillis()).toFile();
                    apiClient.downloadFile(fileId, tempFile).get();
                    
                    Platform.runLater(() -> {
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("Save File");
                        fileChooser.setInitialFileName(fileInfo.getFileName());
                        Stage stage = (Stage) getScene().getWindow();
                        File saveFile = fileChooser.showSaveDialog(stage);
                        
                        if (saveFile != null) {
                            try {
                                Files.copy(tempFile.toPath(), saveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                Files.deleteIfExists(tempFile.toPath());
                                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                                alert.setContentText("File saved to: " + saveFile.getAbsolutePath());
                                alert.show();
                            } catch (IOException ex) {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setContentText("Failed to save file: " + ex.getMessage());
                                alert.show();
                            }
                        } else {
                            try {
                                Files.deleteIfExists(tempFile.toPath());
                            } catch (IOException ex) {
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
            
            encryptionProgress.setVisible(true);
            new Thread(downloadTask).start();
            return;
        }
        
        // Encrypted file download (original logic)
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
     * Files received from server are always encrypted, so we save them with type "ENCRYPTED_FILE".
     */
    private void saveMessageToDatabase(String sender, String messageText, boolean isEncryptedFile, String fileId) {
        saveMessageToDatabase(sender, messageText, isEncryptedFile, fileId, null);
    }
    
    private void saveMessageToDatabase(String sender, String messageText, boolean isEncryptedFile, String fileId, String localFilePath) {
        try {
            // Determine message type and prepare message content
            String type;
            String messageContent = messageText;
            
            if (fileId != null && !fileId.isEmpty()) {
                // Files from server are always encrypted, save as ENCRYPTED_FILE
                // This allows us to distinguish them when loading from DB
                type = "ENCRYPTED_FILE"; // Always mark as encrypted since files from server are encrypted
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
                    .localFilePath(localFilePath)
                    .build();
            
            messageDatabase.saveMessage(localMessage);
        } catch (Exception e) {
            System.err.println("Failed to save message to database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load messages from the local database and display them.
     * Files from database: treat as unencrypted for display logic (they need to be decrypted when downloaded from server).
     */
    private void loadMessagesFromDatabase() {
        try {
            List<LocalMessage> messages = messageDatabase.loadMessages(chat.getId());
            Platform.runLater(() -> {
                for (LocalMessage msg : messages) {
                    // Determine if it's a file message
                    boolean isFile = "FILE".equals(msg.getType()) || "ENCRYPTED_FILE".equals(msg.getType());
                    String displayText = msg.getMessage();
                    
                    // Add prefix for encrypted text messages if needed
                    if ("ENCRYPTED".equals(msg.getType()) && !displayText.startsWith("[ENCRYPTED]")) {
                        displayText = "[ENCRYPTED] " + displayText;
                    }
                    
                    // Display message without saving to database (it's already in the database)
                    // Files from DB: use local file path if available, otherwise use fileId
                    if (isFile && msg.getLocalFilePath() != null && !msg.getLocalFilePath().isEmpty()) {
                        // Use local file path
                        displayMessageInUIFromDB(msg.getSender(), displayText, msg.getLocalFilePath());
                    } else {
                        // Fallback to fileId (for old messages without local path)
                        displayMessageInUI(msg.getSender(), displayText, false, isFile ? msg.getFileId() : null);
                    }
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
     * Files from database are considered unencrypted (isEncryptedFile = false) for display purposes,
     * but they are still encrypted on the server and will be decrypted when downloaded.
     */
    private void displayMessageInUI(String sender, String messageText, boolean isEncryptedFile, String fileId) {
        // Files loaded from database: treat as unencrypted for display (they were already processed)
        // Note: Files are still encrypted on server, but we mark them as unencrypted here
        // so displayImageInMessage won't try to decrypt them (they'll be handled as regular downloads)
        boolean fileIsEncrypted = false; // Files from DB are treated as unencrypted
        
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 10, 5, 10));
        messageBox.setAlignment(Pos.CENTER_LEFT);
        
        Label senderLabel = new Label(sender + ":");
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        if (fileId != null && !fileId.isEmpty()) {
            // Check if this is an image file
            System.out.println("[displayMessageInUI] Checking file for image display: fileId=" + fileId + " (from DB, treated as unencrypted)");
            apiClient.getFileInfo(fileId).thenAccept(fileInfo -> {
                Platform.runLater(() -> {
                    String fileName = fileInfo != null ? fileInfo.getFileName() : null;
                    System.out.println("[displayMessageInUI] File info received: fileName=" + fileName + ", fileId=" + fileId);
                    if (fileName != null && isImageFile(fileName)) {
                        System.out.println("[displayMessageInUI] File is an image, displaying inline: " + fileName);
                        // Display image inline (treated as unencrypted from DB, but will decrypt when downloading)
                        displayImageInMessage(messageBox, senderLabel, fileId, fileIsEncrypted, fileInfo);
                    } else {
                        System.out.println("[displayMessageInUI] File is not an image or fileName is null, showing as attachment: " + fileName);
                        // Display file attachment with download button
                        displayFileAttachment(messageBox, senderLabel, fileId, fileIsEncrypted);
                    }
                });
            }).exceptionally(ex -> {
                System.err.println("[displayMessageInUI] Failed to get file info for fileId=" + fileId + ": " + ex.getMessage());
                ex.printStackTrace();
                // If we can't get file info, show as regular file attachment
                Platform.runLater(() -> {
                    displayFileAttachment(messageBox, senderLabel, fileId, fileIsEncrypted);
                });
                return null;
            });
        } else {
            // Regular text message
            Text messageTextNode = new Text(messageText);
            messageTextNode.setWrappingWidth(600);
            messageTextNode.setStyle("-fx-font-family: monospace; -fx-fill: #333;");
            messageBox.getChildren().addAll(senderLabel, messageTextNode);
            messageContainer.getChildren().add(messageBox);
        }
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
    
    /**
     * Download file to local storage after sending.
     * Returns the local file path.
     */
    private CompletableFuture<String> downloadFileToLocalStorage(String fileId, String originalFileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create local storage directory
                Path storageDir = Paths.get(System.getProperty("user.home"), ".crypto-client", "files", chat.getId().toString());
                Files.createDirectories(storageDir);
                
                // Generate local file name
                String fileName = originalFileName != null ? originalFileName : "file_" + fileId;
                Path localFile = storageDir.resolve(fileId + "_" + fileName);
                
                // Download encrypted file
                Path encryptedFileWithIV = storageDir.resolve("encrypted_with_iv_" + fileId);
                File tempEncryptedFile = encryptedFileWithIV.toFile();
                apiClient.downloadFile(fileId, tempEncryptedFile).get();
                
                // Extract IV and decrypt
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
                Path encryptedFile = storageDir.resolve("encrypted_" + fileId);
                try (FileInputStream fis = new FileInputStream(tempEncryptedFile);
                     FileOutputStream fos = new FileOutputStream(encryptedFile.toFile())) {
                    fis.skip(blockSize);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                Files.deleteIfExists(encryptedFileWithIV);
                
                // Decrypt file
                SymmetricCipher cipher = EncryptionUtil.createCipher(
                    chat.getAlgorithm(),
                    chat.getMode(),
                    chat.getPadding(),
                    key,
                    iv
                );
                
                cipher.decryptFile(encryptedFile, localFile);
                Files.deleteIfExists(encryptedFile);
                
                return localFile.toAbsolutePath().toString();
            } catch (Exception e) {
                System.err.println("Failed to download file to local storage: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Display message from database with local file path.
     */
    private void displayMessageInUIFromDB(String sender, String messageText, String localFilePath) {
        HBox messageBox = new HBox(10);
        messageBox.setPadding(new Insets(5, 10, 5, 10));
        messageBox.setAlignment(Pos.CENTER_LEFT);
        
        Label senderLabel = new Label(sender + ":");
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        if (localFilePath != null && !localFilePath.isEmpty() && Files.exists(Paths.get(localFilePath))) {
            File localFile = new File(localFilePath);
            String fileName = localFile.getName();
            if (isImageFile(fileName)) {
                // Display image from local file
                displayImageFromLocalFile(messageBox, senderLabel, localFilePath);
            } else {
                // Display file attachment with local path
                displayFileAttachmentFromLocal(messageBox, senderLabel, localFilePath);
            }
        } else {
            // Regular text message
            Text messageTextNode = new Text(messageText);
            messageTextNode.setWrappingWidth(600);
            messageTextNode.setStyle("-fx-font-family: monospace; -fx-fill: #333;");
            messageBox.getChildren().addAll(senderLabel, messageTextNode);
            messageContainer.getChildren().add(messageBox);
        }
    }
    
    /**
     * Display image from local file.
     */
    private void displayImageFromLocalFile(HBox messageBox, Label senderLabel, String localFilePath) {
        VBox imageContainer = new VBox(5);
        imageContainer.setPadding(new Insets(5));
        
        // Add sender label
        imageContainer.getChildren().add(senderLabel);
        
        // Load image from local file
        try {
            Image image = new Image("file:" + localFilePath);
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(400);
            imageView.setFitHeight(400);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            
            imageContainer.getChildren().add(imageView);
            messageBox.getChildren().add(imageContainer);
            messageContainer.getChildren().add(messageBox);
        } catch (Exception e) {
            System.err.println("Failed to load image from local file: " + e.getMessage());
            e.printStackTrace();
            // Fallback to file attachment
            displayFileAttachmentFromLocal(messageBox, senderLabel, localFilePath);
        }
    }
    
    /**
     * Display file attachment from local file.
     * Only used for non-image files.
     */
    private void displayFileAttachmentFromLocal(HBox messageBox, Label senderLabel, String localFilePath) {
        VBox fileContainer = new VBox(5);
        fileContainer.setPadding(new Insets(5));
        
        File localFile = new File(localFilePath);
        String fileName = localFile.getName();
        long fileSize = localFile.length();
        
        Label fileLabel = new Label(fileName + " (" + formatFileSize(fileSize) + ")");
        fileLabel.setStyle("-fx-text-fill: #0066cc; -fx-underline: true;");
        fileLabel.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().open(localFile);
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Failed to open file: " + ex.getMessage());
                alert.show();
            }
        });
        fileLabel.setCursor(javafx.scene.Cursor.HAND);
        
        fileContainer.getChildren().addAll(senderLabel, fileLabel);
        messageBox.getChildren().add(fileContainer);
        messageContainer.getChildren().add(messageBox);
    }
}

