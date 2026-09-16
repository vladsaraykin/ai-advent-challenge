package com.github.vladsaraykin.aichat.user.application;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.user.domain.*;
import com.github.vladsaraykin.aichat.user.infrastructure.FileUserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder encoder;
    public UserService(UserRepository repository, PasswordEncoder encoder) {
        this.repository = repository; this.encoder = encoder;
    }
    public UserProfile register(String username, String password, String displayName) {
        String normalized = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
        FileUserRepository.validateUsername(normalized);
        if (password == null || password.length() < 8 || password.length() > 200)
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Пароль должен содержать от 8 до 200 символов");
        String name = displayName == null || displayName.isBlank() ? normalized : displayName;
        var profile = new UserProfile(normalized, name, "Кратко и по существу",
                "Структурированный Markdown", List.of(), 0, Instant.now());
        return repository.create(new UserAccount(normalized, encoder.encode(password), Instant.now()), profile);
    }
    public UserProfile profile(String username) { return repository.profile(username); }
    public UserProfile update(String username, long version, String displayName, String style,
                              String format, List<String> constraints) {
        try {
            return repository.saveProfile(username, version, new UserProfile(username, displayName, style, format,
                    constraints, version + 1, Instant.now()));
        } catch (IllegalArgumentException e) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Проверьте поля профиля и ограничения");
        }
    }
}
