package dora.crypto;

import dora.crypto.api.ApiClient;
import dora.crypto.shared.dto.Chat;
import dora.crypto.ui.AuthView;
import dora.crypto.ui.ChatView;
import dora.crypto.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;


public class Main extends Application {
    private Stage primaryStage;
    private ApiClient apiClient;
    private AuthView authView;
    private MainView mainView;
    public ChatView currentChatView;
    private Scene mainViewScene;
    private Scene authViewScene;
    public ChatWebSocketClient socket;
    public String UserName = "";

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        this.apiClient = new ApiClient();

        // Set up callback for when token becomes invalid
        apiClient.setOnTokenInvalid(message -> {
            Platform.runLater(() -> {
                System.out.println("Token invalid: " + message);
                showAuthView();
            });
        });

        // Create views
        authView = new AuthView(apiClient, this::onAuthSuccess, this);
        mainView = new MainView(apiClient, this);

        // Set up logout handler
        mainView.addEventHandler(MainView.LOGOUT_EVENT, e -> showAuthView());

        // Show initial auth view
        showAuthView();

        stage.setTitle("Crypto Chat Client");
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
    }

    private void showAuthView() {
        // Reuse the existing authViewScene instead of creating a new one
        if (authViewScene != null) {
            primaryStage.setScene(authViewScene);
        } else {
            // Create scene if it doesn't exist (first time)
            authViewScene = new Scene(authView, 600, 500);
            primaryStage.setScene(authViewScene);
        }
    }

    private void onAuthSuccess(String username) {
        Platform.runLater(() -> {
            mainView.setCurrentUser(username);
            // Reuse the existing mainViewScene instead of creating a new one
            if (mainViewScene != null) {
                primaryStage.setScene(mainViewScene);
            } else {
                // Create scene if it doesn't exist (first time)
                mainViewScene = new Scene(mainView, 900, 700);
                primaryStage.setScene(mainViewScene);
            }
        });
    }

    public boolean manageConnectToChat(Chat chat) {
        socket = new ChatWebSocketClient("ws:" + ApiClient.BASE_URL_NO_PROTOCOL, chat, UserName);
        
        // Set up callback to call connectToChat after subscription is ready
        // This ensures we're subscribed before the server sends "ready for key exchange"
        socket.setOnSubscriptionReady(() -> {
            System.out.println("Subscription ready, calling connectToChat for chat " + chat.getId());
            apiClient.connectToChat(chat.getId());
        });
        
        socket.start();
        return true;
    }

    public void showChatView(Chat chat) {
        Platform.runLater(() -> {
            currentChatView = new ChatView(apiClient, chat, this);
            Scene scene = new Scene(currentChatView, 900, 700);
            primaryStage.setScene(scene);
        });
    }

    public void showMainView() {
        Platform.runLater(() -> {
            currentChatView = null;
            // Reuse the existing mainViewScene instead of creating a new one
            if (mainViewScene != null) {
                primaryStage.setScene(mainViewScene);
            } else {
                // Fallback: create scene if it doesn't exist (shouldn't happen normally)
                mainViewScene = new Scene(mainView, 900, 700);
                primaryStage.setScene(mainViewScene);
            }
            if (mainView != null) {
                mainView.refreshChats();
            }
        });
    }

    public static void main(String[] args) {
        launch();
    }
}
