package com.github.vladsaraykin.aichat.config;

import com.github.vladsaraykin.aichat.user.application.UserRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(UserRepository repository) {
        return username -> repository.account(username.toLowerCase(java.util.Locale.ROOT))
                .map(account -> User.withUsername(account.username()).password(account.passwordHash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"message\":\"Требуется вход в профиль\"}");
                })).build();
    }
}
