package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.agent.api.ChatExceptionHandler;
import com.github.vladsaraykin.aichat.config.SecurityConfiguration;
import com.github.vladsaraykin.aichat.review.api.CodeReviewController;
import com.github.vladsaraykin.aichat.review.application.*;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.UserAccount;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodeReviewControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, ChatExceptionHandler.class})
    static class Config {
        @Bean UserRepository usersRepository() {
            var repository = mock(UserRepository.class);
            when(repository.account("vlad")).thenReturn(Optional.of(new UserAccount("vlad",
                    new BCryptPasswordEncoder().encode("test-password"), Instant.now())));
            return repository;
        }
        @Bean CodeReviewService reviews() { return mock(CodeReviewService.class); }
        @Bean CodeReviewController controller(CodeReviewService reviews) { return new CodeReviewController(reviews); }
    }
    @Test void requiresAuthenticationAndTakesOwnerOnlyFromPrincipal() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var service = context.getBean(CodeReviewService.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            var request = CodeReviewServiceTest.request();
            String auth = "Basic " + Base64.getEncoder().encodeToString("vlad:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String body = JsonMapper.builder().build().writeValueAsString(request);
            mvc.perform(post("/api/reviews").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/reviews/" + request.requestId())).andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
            when(service.get("vlad", request.requestId())).thenReturn(new ReviewRepository.Entry("hash", "RUNNING", null));
            mvc.perform(get("/api/reviews/" + request.requestId()).param("owner", "other").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
            mvc.perform(post("/api/reviews").param("owner", "other").header("Authorization", auth)
                    .contentType("application/json").content(body)).andExpect(status().isOk());
            verify(service).review(eq("vlad"), eq(request));
            verify(service, never()).review(eq("other"), any());
            mvc.perform(post("/api/reviews").header("Authorization", auth).contentType("application/json")
                    .content(body.replace(request.baseSha(), "bad-sha"))).andExpect(status().isBadRequest());
        }
    }
}
