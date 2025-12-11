package dora.crypto.ui;

import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Contact;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

public class ContactManagementView extends VBox {
    private final ApiClient apiClient;
    private ListView<Contact> contactListView;
    private TextField addContactField;
    private Label statusLabel;

    public ContactManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;
        createView();
        // Don't load contacts in constructor - wait until after authentication
    }

    private void createView() {
        setSpacing(10);
        setPadding(new Insets(15));

        Label titleLabel = new Label("Contact Management");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Add contact section
        HBox addContactBox = new HBox(10);
        addContactField = new TextField();
        addContactField.setPromptText("Enter username to add");
        Button addButton = new Button("Add Contact");
        addButton.setOnAction(e -> handleAddContact());
        addContactBox.getChildren().addAll(addContactField, addButton);

        // Contact list
        contactListView = new ListView<>();
        contactListView.setPrefHeight(300);
        contactListView.setCellFactory(param -> new ContactListCell(apiClient, this));

        // Action buttons
        HBox actionBox = new HBox(10);
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadContacts());
        actionBox.getChildren().add(refreshButton);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: red;");

        getChildren().addAll(titleLabel, addContactBox, contactListView, actionBox, statusLabel);
    }

    private void handleAddContact() {
        String username = addContactField.getText().trim();
        if (username.isEmpty()) {
            statusLabel.setText("Please enter a username");
            return;
        }

        statusLabel.setText("Adding contact...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.addContact(username)
                .thenRun(() -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Contact added successfully!");
                        statusLabel.setStyle("-fx-text-fill: green;");
                        addContactField.clear();
                        loadContacts();
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed to add contact: " + ex.getCause().getMessage());
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    public void loadContacts() {
        statusLabel.setText("Loading contacts...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.getContacts()
                .thenAccept(contacts -> {
                    Platform.runLater(() -> {
                        contactListView.getItems().clear();
                        contactListView.getItems().addAll(contacts);
                        statusLabel.setText("Loaded " + contacts.size() + " contacts");
                        statusLabel.setStyle("-fx-text-fill: green;");
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

    private static class ContactListCell extends ListCell<Contact> {
        private final ApiClient apiClient;
        private final ContactManagementView parentView;
        private HBox cellBox;
        private Label usernameLabel;
        private Label statusLabel;
        private HBox buttonBox;

        public ContactListCell(ApiClient apiClient, ContactManagementView parentView) {
            this.apiClient = apiClient;
            this.parentView = parentView;
            createCell();
        }

        private void createCell() {
            cellBox = new HBox(10);
            cellBox.setPadding(new Insets(5));
            cellBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            usernameLabel = new Label();
            statusLabel = new Label();
            statusLabel.setStyle("-fx-font-size: 10px;");

            buttonBox = new HBox(5);

            cellBox.getChildren().addAll(usernameLabel, statusLabel, new javafx.scene.control.Separator(), buttonBox);
        }

        @Override
        protected void updateItem(Contact contact, boolean empty) {
            super.updateItem(contact, empty);

            if (empty || contact == null) {
                setGraphic(null);
                return;
            }

            usernameLabel.setText(contact.getUsername());
            statusLabel.setText("(" + contact.getStatus() + ")");

            buttonBox.getChildren().clear();

            if ("PENDING".equals(contact.getStatus())) {
                Button confirmButton = new Button("Confirm");
                confirmButton.setOnAction(e -> handleConfirm(contact));
                Button rejectButton = new Button("Reject");
                rejectButton.setOnAction(e -> handleReject(contact));
                buttonBox.getChildren().addAll(confirmButton, rejectButton);
            }

            Button deleteButton = new Button("Delete");
            deleteButton.setOnAction(e -> handleDelete(contact));
            buttonBox.getChildren().add(deleteButton);

            setGraphic(cellBox);
        }

        private void handleConfirm(Contact contact) {
            apiClient.confirmContact(contact.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadContacts()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Failed to confirm contact: " + ex.getCause().getMessage());
                            alert.show();
                        });
                        return null;
                    });
        }

        private void handleReject(Contact contact) {
            apiClient.rejectContact(contact.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadContacts()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Failed to reject contact: " + ex.getCause().getMessage());
                            alert.show();
                        });
                        return null;
                    });
        }

        private void handleDelete(Contact contact) {
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("Delete Contact");
            confirmAlert.setHeaderText("Are you sure you want to delete " + contact.getUsername() + "?");
            confirmAlert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    apiClient.deleteContact(contact.getId())
                            .thenRun(() -> Platform.runLater(() -> parentView.loadContacts()))
                            .exceptionally(ex -> {
                                Platform.runLater(() -> {
                                    Alert alert = new Alert(Alert.AlertType.ERROR);
                                    alert.setContentText("Failed to delete contact: " + ex.getCause().getMessage());
                                    alert.show();
                                });
                                return null;
                            });
                }
            });
        }
    }
}

