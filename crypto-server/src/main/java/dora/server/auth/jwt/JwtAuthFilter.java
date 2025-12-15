package dora.server.auth.jwt;

import dora.server.auth.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String HEADER_NAME = "Authorization";
    private final JwtService jwtService;
    private final UserService userService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Получаем токен из заголовка
        var authHeader = request.getHeader(HEADER_NAME);
        if (StringUtils.isEmpty(authHeader) || !StringUtils.startsWith(authHeader, BEARER_PREFIX)) {
            // No token provided - let Spring Security handle authorization
            // If endpoint requires authentication, Spring Security will reject it
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Обрезаем префикс и получаем имя пользователя из токена
            var jwt = authHeader.substring(BEARER_PREFIX.length());
            var username = jwtService.extractUserName(jwt);

            if (StringUtils.isNotEmpty(username) && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails userDetails = userService
                            .userDetailsService()
                            .loadUserByUsername(username);

                    // Если токен валиден, то аутентифицируем пользователя
                    if (jwtService.isTokenValid(jwt, userDetails)) {
                        SecurityContext context = SecurityContextHolder.createEmptyContext();

                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        context.setAuthentication(authToken);
                        SecurityContextHolder.setContext(context);
                    } else {
                        // Token is invalid (expired or doesn't match user)
                        System.err.println("Invalid JWT token for user: " + username);
                    }
                } catch (Exception e) {
                    // User not found or other error loading user
                    System.err.println("Error loading user for JWT token: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // JWT parsing failed (malformed token, expired, etc.)
            System.err.println("JWT parsing error: " + e.getMessage());
            // Continue filter chain - Spring Security will handle authorization
        }
        
        filterChain.doFilter(request, response);
    }
}
