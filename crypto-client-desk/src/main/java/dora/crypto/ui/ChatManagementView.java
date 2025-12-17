package dora.crypto.ui;

import dora.crypto.Main;
import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.Contact;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class ChatManagementView extends VBox {
    private final ApiClient apiClient;
    private ListView<Chat> chatListView;
    private ComboBox<Contact> contactComboBox;
    private ComboBox<String> algorithmComboBox;
    private ComboBox<String> modeComboBox;
    private ComboBox<String> paddingComboBox;
    private Label statusLabel;
    private static Main app;

    public ChatManagementView(ApiClient apiClient, Main app) {
        this.apiClient = apiClient;
        ChatManagementView.app = app;
        createView();
        // Don't load data in constructor - wait until after authentication
    }

    private void createView() {
        setSpacing(10);
        setPadding(new Insets(15));

        Label titleLabel = new Label("Secret Chat Management");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Create chat section
        VBox createChatBox = createCreateChatSection();

        // Chat list
        chatListView = new ListView<>();
        chatListView.setPrefHeight(300);
        chatListView.setCellFactory(param -> new ChatListCell(apiClient, this));

        // Action buttons
        HBox actionBox = new HBox(10);
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            updateFull();
        });
        actionBox.getChildren().add(refreshButton);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: red;");

        getChildren().addAll(titleLabel, createChatBox, chatListView, actionBox, statusLabel);
        updateFull();
    }

    public void updateFull(){
        loadChats();
        loadContacts();
    }

    private VBox createCreateChatSection() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-border-color: gray; -fx-border-radius: 5;");

        Label sectionLabel = new Label("Create New Secret Chat");
        sectionLabel.setStyle("-fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        // Contact selection
        grid.add(new Label("Contact:"), 0, 0);
        contactComboBox = new ComboBox<>();
        contactComboBox.setPrefWidth(200);
        // Set cell factory to display username
        contactComboBox.setCellFactory(param -> new ListCell<Contact>() {
            @Override
            protected void updateItem(Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                if (empty || contact == null) {
                    setText(null);
                } else {
                    setText(contact.getUsername());
                }
            }
        });
        // Set button cell to display username when selected
        contactComboBox.setButtonCell(new ListCell<Contact>() {
            @Override
            protected void updateItem(Contact contact, boolean empty) {
                super.updateItem(contact, empty);
                if (empty || contact == null) {
                    setText(null);
                } else {
                    setText(contact.getUsername());
                }
            }
        });
        grid.add(contactComboBox, 1, 0);

        // Algorithm selection
        grid.add(new Label("Algorithm:"), 0, 1);
        algorithmComboBox = new ComboBox<>();
        algorithmComboBox.getItems().addAll("MARS", "RC5");
        algorithmComboBox.setValue("MARS");
        grid.add(algorithmComboBox, 1, 1);

        // Mode selection
        grid.add(new Label("Mode:"), 0, 2);
        modeComboBox = new ComboBox<>();
        modeComboBox.getItems().addAll("CBC", "CFB", "CTR", "ECB", "OFB", "PCBC", "RANDOM_DELTA");
        modeComboBox.setValue("CBC");
        grid.add(modeComboBox, 1, 2);

        // Padding selection
        grid.add(new Label("Padding:"), 0, 3);
        paddingComboBox = new ComboBox<>();
        paddingComboBox.getItems().addAll("ANSI_X923", "ISO_10126", "PKCS7", "ZEROS");
        paddingComboBox.setValue("PKCS7");
        grid.add(paddingComboBox, 1, 3);

        Button createButton = new Button("Create Chat");
        createButton.setOnAction(e -> handleCreateChat());
        grid.add(createButton, 1, 4);

        box.getChildren().addAll(sectionLabel, grid);
        return box;
    }

    public void loadContacts() {
        apiClient.getContacts()
                .thenAccept(contacts -> {
                    Platform.runLater(() -> {
                        contactComboBox.getItems().clear();
                        // Only show confirmed contacts
                        contacts.stream()
                                .filter(c -> "CONFIRMED".equals(c.getStatus()))
                                .forEach(c -> contactComboBox.getItems().add(c));
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed to load contacts: " + ex.getCause().getMessage());
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    private void handleCreateChat() {
        Contact selectedContact = contactComboBox.getSelectionModel().getSelectedItem();
        if (selectedContact == null) {
            statusLabel.setText("Please select a contact");
            return;
        }

        String algorithm = algorithmComboBox.getValue();
        String mode = modeComboBox.getValue();
        String padding = paddingComboBox.getValue();

        statusLabel.setText("Creating chat...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.createChat(selectedContact.getId(), algorithm, mode, padding)
                .thenAccept(chat -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Chat created successfully!");
                        statusLabel.setStyle("-fx-text-fill: green;");
                        loadChats();
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        String errorMessage = getChatErrorMessage(ex);
                        statusLabel.setText(errorMessage);
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    /**
     * Extracts and formats user-friendly error messages for chat creation errors.
     */
    private String getChatErrorMessage(Throwable ex) {
        if (ex == null) {
            return "Failed to create chat. Please try again.";
        }

        Throwable cause = ex.getCause();
        if (cause == null) {
            cause = ex;
        }

        String message = cause.getMessage();
        if (message == null || message.isEmpty()) {
            return "Failed to create chat. Please try again.";
        }

        // Check for specific error patterns
        String lowerMessage = message.toLowerCase();
        
        // Chat already exists
        if (lowerMessage.contains("chat already exists") || 
            lowerMessage.contains("chat already exists with this contact")) {
            return "A chat with this contact already exists. Please select a different contact or use the existing chat.";
        }

        // Contact not found
        if (lowerMessage.contains("contact not found")) {
            return "The selected contact was not found. Please refresh and try again.";
        }

        // Contact not confirmed
        if (lowerMessage.contains("contact must be confirmed")) {
            return "The selected contact must be confirmed before creating a chat.";
        }

        // Permission errors
        if (lowerMessage.contains("can only create chats with your contacts")) {
            return "You can only create chats with your contacts.";
        }

        // Return the original message if it's already user-friendly, otherwise provide a generic message
        return message;
    }

    public void loadChats() {
        statusLabel.setText("Loading chats...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.getChats()
                .thenAccept(chats -> {
                    Platform.runLater(() -> {
                        chatListView.getItems().clear();
                        chatListView.getItems().addAll(chats);
                        statusLabel.setText("Loaded " + chats.size() + " chats");
                        statusLabel.setStyle("-fx-text-fill: green;");
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed to load chats: " + ex.getCause().getMessage());
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    private static class ChatListCell extends ListCell<Chat> {
        private final ApiClient apiClient;
        private final ChatManagementView parentView;
        private HBox cellBox;
        private VBox infoBox;
        private HBox buttonBox;

        public ChatListCell(ApiClient apiClient, ChatManagementView parentView) {
            this.apiClient = apiClient;
            this.parentView = parentView;
            createCell();
        }

        private void createCell() {
            cellBox = new HBox(10);
            cellBox.setPadding(new Insets(5));
            cellBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            infoBox = new VBox(3);
            buttonBox = new HBox(5);

            cellBox.getChildren().addAll(infoBox, new javafx.scene.control.Separator(), buttonBox);
        }

        @Override
        protected void updateItem(Chat chat, boolean empty) {
            super.updateItem(chat, empty);

            if (empty || chat == null) {
                setGraphic(null);
                return;
            }

            infoBox.getChildren().clear();
            infoBox.getChildren().addAll(
                    new Label("Contact: " + chat.getContactUsername()),
                    new Label("Algorithm: " + chat.getAlgorithm() + " | Mode: " + chat.getMode() + " | Padding: " + chat.getPadding()),
                    new Label("Status: " + chat.getStatus())
            );

            buttonBox.getChildren().clear();

            if (!"CONNECTED".equals(chat.getStatus())) {
                Button connectButton = new Button("Connect");
                connectButton.setOnAction(e -> handleConnect(chat));
                buttonBox.getChildren().add(connectButton);
            }

            if ("CONNECTED".equals(chat.getStatus())) {
                Button disconnectButton = new Button("Disconnect");
                disconnectButton.setOnAction(e -> handleDisconnect(chat));
                buttonBox.getChildren().add(disconnectButton);
            }

            // Add delete button for all chats
            Button deleteButton = new Button("Delete");
            deleteButton.setStyle("-fx-background-color: #ff4444; -fx-text-fill: white;");
            deleteButton.setOnAction(e -> handleDelete(chat));
            buttonBox.getChildren().add(deleteButton);

            setGraphic(cellBox);
        }

        private void handleConnect(Chat chat) {
            var ok = app.manageConnectToChat(chat);
            parentView.loadChats();

            if (ok) {
                app.showChatView(chat);
            }
        }

        private void handleDisconnect(Chat chat) {
            // Disconnect socket if it's for this chat
            if (app.socket != null && app.socket.getChat() != null && app.socket.getChat().getId().equals(chat.getId())) {
                app.manageDisconnect();
            }
            
            apiClient.disconnectFromChat(chat.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadChats()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Failed to disconnect from chat: " + ex.getCause().getMessage());
                            alert.show();
                        });
                        return null;
                    });
        }

        private void handleDelete(Chat chat) {
            // Show confirmation dialog
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("Delete Chat");
            confirmAlert.setHeaderText("Delete Secret Chat");
            confirmAlert.setContentText("Are you sure you want to delete the chat with " + chat.getContactUsername() + "? This action cannot be undone.");

            confirmAlert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    apiClient.deleteChat(chat.getId())
                            .thenRun(() -> Platform.runLater(() -> {
                                // If user is currently viewing this chat, delete messages and switch to main view
                                if (app.currentChatView != null) {
                                    try {
                                        var currentChat = app.currentChatView.getChat();
                                        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
                                            // Delete messages from database before switching views
                                            app.currentChatView.deleteChatMessages();
                                            app.showMainView();
                                        }
                                    } catch (Exception e) {
                                        // Chat view might be invalid, just switch to main view
                                        app.showMainView();
                                    }
                                }
                                parentView.loadChats();
                                Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                                successAlert.setTitle("Chat Deleted");
                                successAlert.setContentText("Chat deleted successfully.");
                                successAlert.show();
                            }))
                            .exceptionally(ex -> {
                                Platform.runLater(() -> {
                                    Alert alert = new Alert(Alert.AlertType.ERROR);
                                    alert.setTitle("Delete Failed");
                                    alert.setContentText("Failed to delete chat: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                                    alert.show();
                                });
                                return null;
                            });
                }
            });
        }
    }
}

