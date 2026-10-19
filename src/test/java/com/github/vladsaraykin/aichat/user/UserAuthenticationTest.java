package com.github.vladsaraykin.aichat.user;

import com.github.vladsaraykin.aichat.agent.api.ChatExceptionHandler;
import com.github.vladsaraykin.aichat.user.api.UserController;
import com.github.vladsaraykin.aichat.user.application.UserService;
import com.github.vladsaraykin.aichat.user.infrastructure.FileUserRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserAuthenticationTest {
    @TempDir Path directory;
    @Test void storesHashedCredentialsAndVersionedProfileOnFilesystem() throws Exception {
        var repository = new FileUserRepository(directory.toString());
        var encoder = new BCryptPasswordEncoder();
        var service = new UserService(repository, encoder);
        var mvc = MockMvcBuilders.standaloneSetup(new UserController(service))
                .setControllerAdvice(new ChatExceptionHandler()).build();

        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                {"username":"alice","password":"secret-123","displayName":"Алиса","birthDate":"1990-05-20"}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.birthDate").value("1990-05-20"));
        var account = repository.account("alice").orElseThrow();
        assertThat(account.passwordHash()).doesNotContain("secret-123");
        assertThat(encoder.matches("secret-123", account.passwordHash())).isTrue();

        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                {"username":"future","password":"secret-123","displayName":"Future","birthDate":"2999-01-01"}
                """)).andExpect(status().isBadRequest());

        var updated = service.update("alice", 0, "Алиса", "Очень кратко", "Только JSON", java.util.List.of("Без emoji"));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.birthDate()).isEqualTo(java.time.LocalDate.of(1990, 5, 20));
        assertThat(new FileUserRepository(directory.toString()).profile("alice").constraints()).containsExactly("Без emoji");
        assertThatThrownBy(() -> service.update("alice", 0, "Алиса", "Подробно", "Markdown", java.util.List.of()))
                .hasMessageContaining("изменился");
    }
}
