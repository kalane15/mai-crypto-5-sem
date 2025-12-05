package dora.crypto;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Main extends Application {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void start(Stage stage) {

        TextArea output = new TextArea();
        output.setPrefHeight(300);

        Button sendBtn = new Button("Отправить запрос");

        sendBtn.setOnAction(event -> sendRequest(output));

        VBox root = new VBox(10, sendBtn, output);
        root.setPadding(new Insets(10));

        stage.setTitle("REST Client Example");
        stage.setScene(new Scene(root, 400, 350));
        stage.show();
    }

    private void sendRequest(TextArea output) {
        // Укажи URL своего REST сервера
        String url = "http://localhost:8080/api/hello";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        // Выполняем запрос в отдельном потоке
        new Thread(() -> {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                String text = "Статус: " + response.statusCode() + "\n\n" + response.body();

                // Обновляем UI в JavaFX-потоке:
                javafx.application.Platform.runLater(() -> output.setText(text));

            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> output.setText("Ошибка: " + e.getMessage()));
            }
        }).start();
    }

    public static void main(String[] args) {
        launch();
    }
}
