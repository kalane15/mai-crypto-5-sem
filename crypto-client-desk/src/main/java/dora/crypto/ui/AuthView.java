package dora.crypto.ui;

import dora.crypto.Main;
import dora.crypto.api.ApiClient;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class AuthView extends VBox {
    private final ApiClient apiClient;
    private final Consumer<String> onAuthSuccess;
    private TextField usernameField;
    private PasswordField passwordField;
    private Label statusLabel;
    private final Main app;

    public AuthView(ApiClient apiClient, Consumer<String> onAuthSuccess, Main app) {
        this.apiClient = apiClient;
        this.onAuthSuccess = onAuthSuccess;
        createView();
        this.app = app;
    }

    private void createView() {
        setSpacing(15);
        setPadding(new Insets(20));
        setAlignment(Pos.CENTER);

        Label titleLabel = new Label("Crypto Chat Client");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        TabPane tabPane = new TabPane();
        
        Tab signInTab = new Tab("Sign In", createSignInForm());
        Tab signUpTab = new Tab("Sign Up", createSignUpForm());
        
        tabPane.getTabs().addAll(signInTab, signUpTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: red;");

        getChildren().addAll(titleLabel, tabPane, statusLabel);
    }

    private GridPane createSignInForm() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        Label usernameLabel = new Label("Username:");
        usernameField = new TextField();
        usernameField.setPromptText("Enter username");

        Label passwordLabel = new Label("Password:");
        passwordField = new PasswordField();
        passwordField.setPromptText("Enter password");

        Button signInButton = new Button("Sign In");
        signInButton.setOnAction(e -> handleSignIn());

        grid.add(usernameLabel, 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(passwordLabel, 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(signInButton, 1, 2);

        return grid;
    }

    private GridPane createSignUpForm() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField signUpUsernameField = new TextField();
        signUpUsernameField.setPromptText("Enter username");

        PasswordField signUpPasswordField = new PasswordField();
        signUpPasswordField.setPromptText("Enter password");

        Button signUpButton = new Button("Sign Up");
        signUpButton.setOnAction(e -> handleSignUp(signUpUsernameField.getText(), signUpPasswordField.getText()));

        grid.add(new Label("Username:"), 0, 0);
        grid.add(signUpUsernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(signUpPasswordField, 1, 1);
        grid.add(signUpButton, 1, 2);

        return grid;
    }

    private void handleSignIn() {
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please fill in all fields");
            return;
        }

        statusLabel.setText("Signing in...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.signIn(username, password)
                .thenAccept(response -> {
                    javafx.application.Platform.runLater(() -> {
                        apiClient.setAuthToken(response.getToken());
                        statusLabel.setText("Sign in successful!");
                        statusLabel.setStyle("-fx-text-fill: green;");
                        app.UserName = username;
                        onAuthSuccess.accept(username);
                    });
                })
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        String errorMessage = getErrorMessage(ex);
                        statusLabel.setText("Sign in failed: " + errorMessage);
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    private void handleSignUp(String username, String password) {
        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please fill in all fields");
            return;
        }

        statusLabel.setText("Signing up...");
        statusLabel.setStyle("-fx-text-fill: blue;");

        apiClient.signUp(username, password)
                .thenAccept(response -> {
                    javafx.application.Platform.runLater(() -> {
                        apiClient.setAuthToken(response.getToken());
                        statusLabel.setText("Sign up successful!");
                        app.UserName = username;
                        statusLabel.setStyle("-fx-text-fill: green;");
                        onAuthSuccess.accept(username);
                    });
                })
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        String errorMessage = getErrorMessage(ex);
                        statusLabel.setText("Sign up failed: " + errorMessage);
                        statusLabel.setStyle("-fx-text-fill: red;");
                    });
                    return null;
                });
    }

    private String getErrorMessage(Throwable ex) {
        if (ex == null) {
            return "Unknown error occurred";
        }

        Throwable cause = ex.getCause();
        if (cause == null) {
            cause = ex;
        }

        String message = cause.getMessage();
        if (message == null || message.isEmpty()) {
            return "An error occurred. Please try again.";
        }

        String lowerMessage = message.toLowerCase();
        
        if (lowerMessage.contains("пользователь с таким именем уже существует") ||
            lowerMessage.contains("user with such name already exists") ||
            lowerMessage.contains("username already exists") ||
            lowerMessage.contains("user already exists")) {
            return "This username is already taken. Please choose a different username.";
        }

        if (lowerMessage.contains("пользователь не найден") ||
            lowerMessage.contains("user not found") ||
            lowerMessage.contains("username not found")) {
            return "Invalid username or password. Please check your credentials and try again.";
        }

        if (lowerMessage.contains("unauthorized") ||
            lowerMessage.contains("forbidden") ||
            lowerMessage.contains("authentication failed") ||
            lowerMessage.contains("bad credentials")) {
            return "Invalid username or password. Please check your credentials and try again.";
        }

        if (lowerMessage.contains("connection") ||
            lowerMessage.contains("timeout") ||
            lowerMessage.contains("network") ||
            lowerMessage.contains("refused")) {
            return "Connection error. Please check your internet connection and try again.";
        }

        message = message.replaceAll("\\d{3}\\s*-\\s*", "");
        message = message.replaceAll("failed:\\s*\\d{3}", "failed");
        message = message.replaceAll("status code:\\s*\\d{3}", "");
        message = message.replaceAll("\\d{3}\\s*\\|", "");
        message = message.replaceAll("^(Sign in|Sign up|Authentication|Registration)\\s+failed:\\s*", "");
        
        if (message.matches(".*\\d{3}.*") || 
            message.toLowerCase().contains("http") ||
            message.toLowerCase().contains("status")) {
            return "An error occurred. Please try again or contact support if the problem persists.";
        }

        return message.trim();
    }
}

