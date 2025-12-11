package dora.crypto.ui;

import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.shared.dto.Contact;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class ChatManagementView extends VBox {
    private final ApiClient apiClient;
    private ListView<Chat> chatListView;
    private ComboBox<Contact> contactComboBox;
    private ComboBox<String> algorithmComboBox;
    private ComboBox<String> modeComboBox;
    private ComboBox<String> paddingComboBox;
    private Label statusLabel;

    public ChatManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;
        createView();
        loadChats();
        loadContacts();
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
            loadChats();
            loadContacts();
        });
        actionBox.getChildren().add(refreshButton);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: red;");

        getChildren().addAll(titleLabel, createChatBox, chatListView, actionBox, statusLabel);
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
        grid.add(contactComboBox, 1, 0);

        // Algorithm selection
        grid.add(new Label("Algorithm:"), 0, 1);
        algorithmComboBox = new ComboBox<>();
        algorithmComboBox.getItems().addAll("DES", "DEAL", "RC5", "RIJNDAEL");
        algorithmComboBox.setValue("DES");
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

    private void loadContacts() {
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
                        statusLabel.setText("Failed to create chat: " + ex.getCause().getMessage());
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
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

            setGraphic(cellBox);
        }

        private void handleConnect(Chat chat) {
            apiClient.connectToChat(chat.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadChats()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Failed to connect to chat: " + ex.getCause().getMessage());
                            alert.show();
                        });
                        return null;
                    });
        }

        private void handleDisconnect(Chat chat) {
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
    }
}

