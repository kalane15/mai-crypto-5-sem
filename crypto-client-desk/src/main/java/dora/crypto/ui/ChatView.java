package dora.crypto.ui;

import dora.crypto.Main;
import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.ChatMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

public class ChatView extends BorderPane {
    private final ApiClient apiClient;
    private final Chat chat;
    private final Main app;
    private TextArea messageArea;
    private TextField messageInput;
    private Button sendButton;
    private Button disconnectButton;
    private Label chatInfoLabel;

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
            // Display received message
            String displayText = message.getSender() + ": " + message.getMessage() + "\n";
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

        // Bottom section with message input and send button
        HBox bottomBox = new HBox(10);
        bottomBox.setPadding(new Insets(10));
        bottomBox.setAlignment(Pos.CENTER_LEFT);

        Label messageLabel = new Label("Message:");
        messageInput = new TextField();
        messageInput.setPrefWidth(400);
        messageInput.setOnAction(e -> handleSendMessage());

        sendButton = new Button("Send");
        sendButton.setOnAction(e -> handleSendMessage());

        bottomBox.getChildren().addAll(messageLabel, messageInput, sendButton);
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

