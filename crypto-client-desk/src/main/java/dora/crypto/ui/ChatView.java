package dora.crypto.ui;

import dora.crypto.EncryptionUtil;
import dora.crypto.Main;
import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import dora.crypto.SymmetricCipher;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Base64;
import java.util.HexFormat;

public class ChatView extends BorderPane {
    private final ApiClient apiClient;
    private final Chat chat;
    private final Main app;
    private TextArea messageArea;
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

    public ChatView(ApiClient apiClient, Chat chat, Main app) {
        this.apiClient = apiClient;
        this.chat = chat;
        this.app = app;
        createView();
        setupWebSocket();
    }

    private void setupWebSocket() {
        // Set up message callback when WebSocket client is available
        if (app.socket != null) {
            app.socket.setOnMessageReceived(this::onMessageReceived);
            // Clear the placeholder message once connected
            Platform.runLater(() -> {
                messageArea.clear();
                if (app.socket.isConnected()) {
                    messageArea.appendText("Chat connected. You can now send messages.\n");
                } else {
                    messageArea.appendText("Connecting to chat...\n");
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

    private void onMessageReceived(ChatMessage message) {
        Platform.runLater(() -> {
            String messageText = message.getMessage();
            String displayText;
            
            // Check if message is encrypted
            if (messageText.startsWith("ENCRYPTED:")) {
                try {
                    // Decrypt the message
                    String base64Data = messageText.substring("ENCRYPTED:".length());
                    byte[] combined = Base64.getDecoder().decode(base64Data);
                    
                    // Get block size to determine IV size
                    byte[] key = app.socket.getKey();
                    if (key == null) {
                        displayText = message.getSender() + ": [Encrypted - Key not available]\n";
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
                        displayText = message.getSender() + ": [ENCRYPTED] " + decryptedMessage + "\n";
                    }
                } catch (Exception e) {
                    displayText = message.getSender() + ": [Failed to decrypt: " + e.getMessage() + "]\n";
                }
            } else {
                // Regular unencrypted message
                displayText = message.getSender() + ": " + messageText + "\n";
            }
            
            messageArea.appendText(displayText);
            // Auto-scroll to bottom
            messageArea.setScrollTop(Double.MAX_VALUE);
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
        messageArea = new TextArea();
        messageArea.setEditable(false);
        messageArea.setWrapText(true);
        messageArea.setPrefRowCount(20);
        messageArea.setStyle("-fx-font-family: monospace;");
        setCenter(messageArea);

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

        encryptionProgress = new ProgressIndicator();
        encryptionProgress.setVisible(false);
        encryptionProgress.setPrefSize(20, 20);

        inputBox.getChildren().addAll(messageLabel, messageInput, sendButton, encryptButton, cancelEncryptButton, encryptionProgress);

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

        bottomBox.getChildren().addAll(inputBox, ivBox);
        setBottom(bottomBox);

        // Add placeholder message
        messageArea.appendText("Chat connected. Messages will appear here.\n");
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
            messageArea.appendText("You: " + message + "\n");
            messageArea.setScrollTop(Double.MAX_VALUE);
            
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
                byte[] messageBytes = message.getBytes("UTF-8");
                
                // Create block cipher to determine block size for IV
                dora.crypto.block.BlockCipher blockCipher = EncryptionUtil.createBlockCipher(chat.getAlgorithm(), key);
                int blockSize = blockCipher.blockSize();
                
                // Use stored IV or generate new one
                byte[] iv;
                if (currentIV != null && currentIV.length == blockSize) {
                    iv = currentIV.clone();
                } else {
                    // Generate new IV if stored one is invalid or missing
                    iv = EncryptionUtil.generateIV(blockSize);
                    // Update stored IV
                    final byte[] finalIV = iv.clone();
                    Platform.runLater(() -> {
                        currentIV = finalIV;
                        updateIVDisplay();
                    });
                }
                
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
                
                // Format: "ENCRYPTED:<base64>"
                return "ENCRYPTED:" + base64Encrypted;
            }
        };

        // Handle task completion
        currentEncryptionTask.setOnSucceeded(e -> {
            String encryptedMessage = currentEncryptionTask.getValue();
            if (encryptedMessage != null) {
                // Display encrypted message indicator
                messageArea.appendText("You: [ENCRYPTED] " + message + "\n");
                messageArea.setScrollTop(Double.MAX_VALUE);
                
                // Send encrypted message
                if (app.socket != null && app.socket.isConnected()) {
                    app.socket.sendMessage(encryptedMessage);
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
                messageArea.appendText("Encryption cancelled.\n");
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
}

