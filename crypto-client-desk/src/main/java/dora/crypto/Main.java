package dora.crypto;

import dora.crypto.api.ApiClient;
import dora.crypto.ui.AuthView;
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

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        this.apiClient = new ApiClient();

        // Create views
        authView = new AuthView(apiClient, this::onAuthSuccess);
        mainView = new MainView(apiClient);
        
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
        Scene scene = new Scene(authView, 600, 500);
        primaryStage.setScene(scene);
    }

    private void onAuthSuccess(String username) {
        Platform.runLater(() -> {
            mainView.setCurrentUser(username);
            Scene scene = new Scene(mainView, 900, 700);
            primaryStage.setScene(scene);
        });
    }

    public static void main(String[] args) {
        launch();
    }
}
