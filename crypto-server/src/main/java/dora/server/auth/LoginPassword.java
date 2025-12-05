package dora.server.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class LoginPassword {
    @JsonProperty("Login")
    public String Login;

    @JsonProperty("Password")
    public String Password;

    @JsonCreator
    public LoginPassword(@JsonProperty("Login") String login,
                         @JsonProperty("Password") String password) {
        this.Login = login;
        this.Password = password;
    }
}
