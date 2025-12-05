package dora.server.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<String> registerUser(@RequestBody LoginPassword body) {
        // Проверяем, существует ли пользователь в базе
        if (userRepository.existsById(body.Login)) {
            return ResponseEntity.status(409).body("User already exists!");
        }

        // Хешируем пароль
        String hashedPassword = passwordEncoder.encode(body.Password);

        // Сохраняем пользователя в базе
        User user = new User(body.Login, hashedPassword);
        userRepository.save(user);

        return ResponseEntity.ok("User registered successfully!");
    }

    @PostMapping("/login")
    public ResponseEntity<String> loginUser(@RequestBody LoginPassword body) {
        // Ищем пользователя по логину
        User user = userRepository.findById(body.Login).orElse(null);

        if (user == null) {
            return ResponseEntity.status(404).body("User not found!");
        }
        List<GrantedAuthority> authorities = List.of( new SimpleGrantedAuthority("ROLE_USER"));
        // Проверяем пароль
        if (passwordEncoder.matches(body.Password, user.getPassword())) {
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    user.getLogin(),
                    null,
                    authorities
            );

            // Устанавливаем аутентификацию в SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            return ResponseEntity.ok("Login successful!");
        } else {
            return ResponseEntity.status(403).body("Invalid password!");
        }
    }
}
