package dora.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import dora.server.auth.LoginPassword;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthControllerTest {

    @LocalServerPort
    private int port;  // Порт на котором будет запускаться приложение

    @Test
    public void testLoginUser() {
        // Подготовка URL запроса
        String url = "http://localhost:" + port + "/auth/register";


        WebTestClient webTestClient  = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        var tmp = new LoginPassword("user", "passwrord");

        webTestClient.post().uri("/api/hello")
                .bodyValue(tmp)
                .exchange()
                .expectStatus().isEqualTo(403);

        webTestClient.post().uri("/auth/register")
                .bodyValue(tmp)
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/auth/login")
                .bodyValue(tmp)
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/api/hello")
                .bodyValue(tmp)
                .exchange()
                .expectStatus().isOk();
    }
}

