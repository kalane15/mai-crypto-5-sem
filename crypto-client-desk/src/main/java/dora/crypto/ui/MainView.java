package dora.crypto.ui;

import dora.crypto.Main;
import dora.crypto.api.ApiClient;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class MainView extends BorderPane {
    private final ApiClient apiClient;
    private String currentUsername;
    private TabPane tabPane;
    private Label welcomeLabel;
    private Main app;

    public MainView(ApiClient apiClient, Main app) {
        this.apiClient = apiClient;
        this.app = app;
        createView();
    }

    private void createView() {
        VBox topBox = new VBox(10);
        topBox.setPadding(new Insets(10));

        HBox headerBox = new HBox(10);
        welcomeLabel = new Label("Welcome!");
        welcomeLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        Button logoutButton = new Button("Logout");
        logoutButton.setOnAction(e -> handleLogout());
        
        headerBox.getChildren().addAll(welcomeLabel, new javafx.scene.control.Separator(), logoutButton);
        headerBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab contactsTab = new Tab("Contacts", new ContactManagementView(apiClient));
        Tab chatsTab = new Tab("Secret Chats", new ChatManagementView(apiClient, app));

        tabPane.getTabs().addAll(contactsTab, chatsTab);

        topBox.getChildren().addAll(headerBox, tabPane);

        setTop(headerBox);
        setCenter(tabPane);
    }

    public void setCurrentUser(String username) {
        this.currentUsername = username;
        if (welcomeLabel != null) {
            welcomeLabel.setText("Welcome, " + username + "!");
        }
        // Load data after authentication succeeds
        loadInitialData();
    }

    private void loadInitialData() {
        // Load data for all tabs
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getContent() instanceof ContactManagementView) {
                ((ContactManagementView) tab.getContent()).loadContacts();
            } else if (tab.getContent() instanceof ChatManagementView) {
                ChatManagementView chatView = (ChatManagementView) tab.getContent();
                chatView.loadChats();
                chatView.loadContacts();
            }
        }
    }

    public void refreshChats() {
        // Refresh data for all tabs
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getContent() instanceof ChatManagementView) {
                ChatManagementView chatView = (ChatManagementView) tab.getContent();
                chatView.loadChats();
            }
        }
    }

    private void handleLogout() {
        apiClient.clearAuthToken();
        // This will be handled by the main application to switch back to auth view
        fireEvent(new javafx.event.Event(MainView.LOGOUT_EVENT));
    }

    public static final javafx.event.EventType<javafx.event.Event> LOGOUT_EVENT = 
            new javafx.event.EventType<>(javafx.event.Event.ANY, "LOGOUT");
}

