package dora.server;

import dora.crypto.shared.dto.AuthResponse;
import dora.crypto.shared.dto.SignInRequest;
import dora.crypto.shared.dto.SignUpRequest;
import dora.server.auth.LoginPassword;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthControllerTest {

    @LocalServerPort
    private int port;  // Порт на котором будет запускаться приложение

    @Test
    public void testLoginUser() {
        // Подготовка URL запроса
        String url = "http://localhost:" + port + "/auth/register";


        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        String jsonBody = "{"
                + "\"username\": \"makarar\","
                + "\"password\": \"12345678\""
                + "}";

        webTestClient.get().uri("/example/hello")
                .exchange()
                .expectStatus().isEqualTo(403);

        webTestClient.post().uri("/auth/sign-up")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonBody)
                .exchange()
                .expectStatus().isOk();


        AuthResponse jwtToken = webTestClient.post().uri("/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonBody)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AuthResponse.class)  // Используем POJO для десериализации
                .getResponseBody()
                .blockFirst(); // Берем первый элемент из потока (ответ токена)

        // 3. Используем сохраненный JWT для авторизованного запроса
        assert jwtToken != null;
        webTestClient.get().uri("/example/hello")
                .header(AUTHORIZATION, "Bearer " + jwtToken.getToken())  // Добавляем JWT в заголовок
                .exchange()
                .expectStatus().isOk();
    }
}

