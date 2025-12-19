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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


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

        apiClient.setOnTokenInvalid(message -> {
            Platform.runLater(() -> {
                System.out.println("Token invalid: " + message);
                showAuthView();
            });
        });

        authView = new AuthView(apiClient, this::onAuthSuccess, this);
        mainView = new MainView(apiClient, this);

        mainView.addEventHandler(MainView.LOGOUT_EVENT, e -> showAuthView());

        showAuthView();

        stage.setTitle("Crypto Chat Client");
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        
        stage.setOnCloseRequest(e -> {
            cleanupOnExit();
        });
        
        stage.show();
    }
    
    @Override
    public void stop() {
        cleanupOnExit();
    }
    
    private void cleanupOnExit() {
        if (currentChatView != null && currentChatView.getChat() != null) {
            try {
                Long chatId = currentChatView.getChat().getId();
                manageDisconnect();
                try {
                    CompletableFuture<Void> disconnectFuture = apiClient.disconnectFromChat(chatId);
                    disconnectFuture.get(2, TimeUnit.SECONDS);
                    System.out.println("Disconnected from chat " + chatId + " on application exit");
                } catch (Exception ex) {
                    System.err.println("Error disconnecting from chat on exit: " + ex.getMessage());
                }
            } catch (Exception ex) {
                System.err.println("Error during cleanup on exit: " + ex.getMessage());
            }
        } else if (socket != null) {
            manageDisconnect();
        }
    }

    private void showAuthView() {
        if (authViewScene != null) {
            primaryStage.setScene(authViewScene);
        } else {
            authViewScene = new Scene(authView, 600, 500);
            primaryStage.setScene(authViewScene);
        }
    }

    private void onAuthSuccess(String username) {
        Platform.runLater(() -> {
            mainView.setCurrentUser(username);
            if (mainViewScene != null) {
                primaryStage.setScene(mainViewScene);
            } else {
                mainViewScene = new Scene(mainView, 900, 700);
                primaryStage.setScene(mainViewScene);
            }
        });
    }

    public boolean manageConnectToChat(Chat chat) {
        socket = new ChatWebSocketClient("ws:" + ApiClient.BASE_URL_NO_PROTOCOL, chat, UserName);
        
        socket.setOnSubscriptionReady(() -> {
            System.out.println("Subscription ready, calling connectToChat for chat " + chat.getId());
            apiClient.connectToChat(chat.getId());
        });
        
        socket.start();
        return true;
    }

    public void manageDisconnect() {
        if (socket != null) {
            System.out.println("Disconnecting and destroying socket");
            socket.disconnect();
            socket = null;
        }
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
            if (mainViewScene != null) {
                primaryStage.setScene(mainViewScene);
            } else {
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
