package dora.server.auth;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    private String login;  // Логин пользователя (primary key)

    private String password;  // Хешированный пароль

    public User() {}

    public User(String login, String password) {
        this.login = login;
        this.password = password;
        this.roles = List.of("ROLE_USER");
    }
    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> roles = new ArrayList<String>();

    // Геттеры и сеттеры
    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
