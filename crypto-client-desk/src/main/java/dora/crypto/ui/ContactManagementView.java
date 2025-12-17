package dora.crypto.ui;

import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Contact;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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
                        String errorMessage = getContactErrorMessage(ex);
                        statusLabel.setText(errorMessage);
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
                        statusLabel.setText("Failed to load contacts");
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
                // Check if this contact request was sent by the current user
                Boolean isSentByMe = contact.getIsSentByMe();
                if (isSentByMe != null && isSentByMe) {
                    // For pending contacts sent by current user, show Cancel button
                    Button cancelButton = new Button("Cancel");
                    cancelButton.setOnAction(e -> handleCancel(contact));
                    buttonBox.getChildren().add(cancelButton);
                } else {
                    // For pending contacts received by current user, show Confirm and Reject buttons
                    Button confirmButton = new Button("Confirm");
                    confirmButton.setOnAction(e -> handleConfirm(contact));
                    Button rejectButton = new Button("Reject");
                    rejectButton.setOnAction(e -> handleReject(contact));
                    buttonBox.getChildren().addAll(confirmButton, rejectButton);
                }
            } else {
                // For confirmed contacts, show only Delete button
                Button deleteButton = new Button("Delete");
                deleteButton.setOnAction(e -> handleDelete(contact));
                buttonBox.getChildren().add(deleteButton);
            }

            setGraphic(cellBox);
        }

        private void handleConfirm(Contact contact) {
            apiClient.confirmContact(contact.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadContacts()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText(getGeneralErrorMessage(ex));
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
                            alert.setContentText(getGeneralErrorMessage(ex));
                            alert.show();
                        });
                        return null;
                    });
        }

        private void handleCancel(Contact contact) {
            // Cancel a contact request sent by the current user (delete it)
            apiClient.deleteContact(contact.getId())
                    .thenRun(() -> Platform.runLater(() -> parentView.loadContacts()))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText(getGeneralErrorMessage(ex));
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
                                    alert.setContentText(getGeneralErrorMessage(ex));
                                    alert.show();
                                });
                                return null;
                            });
                }
            });
        }
        
        /**
         * Returns a general error message for alerts, hiding technical details and status codes.
         */
        private String getGeneralErrorMessage(Throwable ex) {
            if (ex == null) {
                return "Invalid action";
            }

            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }

            String message = cause.getMessage();
            if (message == null || message.isEmpty()) {
                return "Invalid action";
            }

            // Check if message contains status codes (e.g., "400", "500", "404", etc.)
            if (message.matches(".*\\b\\d{3}\\b.*") || 
                message.contains("status code") || 
                message.contains("StatusCode") ||
                message.contains("HTTP")) {
                return "Invalid action";
            }

            // Check for specific user-friendly messages that should be shown
            String lowerMessage = message.toLowerCase();
            
            // User not found - this is user-friendly
            if (lowerMessage.contains("user not found") || 
                lowerMessage.contains("пользователь не найден")) {
                return "User not found. Please check the username and try again.";
            }

            // Unauthorized - show friendly message
            if (lowerMessage.contains("unauthorized") || 
                lowerMessage.contains("session expired")) {
                return "Session expired. Please sign in again.";
            }

            // For all other errors, show generic message
            return "Invalid action";
        }
    }

    /**
     * Extracts and formats user-friendly error messages for contact operations.
     */
    private String getContactErrorMessage(Throwable ex) {
        if (ex == null) {
            return "Failed to add contact. Please try again.";
        }

        Throwable cause = ex.getCause();
        if (cause == null) {
            cause = ex;
        }

        String message = cause.getMessage();
        if (message == null || message.isEmpty()) {
            return "Failed to add contact. Please try again.";
        }

        // Check for specific error patterns
        String lowerMessage = message.toLowerCase();
        
        // User not found
        if (lowerMessage.contains("user not found") || 
            lowerMessage.contains("пользователь не найден") ||
            lowerMessage.contains("username not found")) {
            return "User not found. Please check the username and try again.";
        }

        // Cannot add yourself
        if (lowerMessage.contains("cannot add yourself") ||
            lowerMessage.contains("add yourself")) {
            return "You cannot add yourself as a contact.";
        }

        // Contact already exists
        if (lowerMessage.contains("contact already exists") ||
            lowerMessage.contains("already exists")) {
            return "This contact already exists in your contact list.";
        }

        // Contact request already exists
        if (lowerMessage.contains("contact request already exists") ||
            lowerMessage.contains("request already exists")) {
            return "A contact request from this user already exists.";
        }

        // Unauthorized - don't show this as a contact error, it's an auth issue
        if (lowerMessage.contains("unauthorized")) {
            return "Session expired. Please sign in again.";
        }

        // Return the original message if it's already user-friendly, otherwise provide a generic message
        return message;
    }
}

