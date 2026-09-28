package com.github.vladsaraykin.aichat.rag;

import com.github.vladsaraykin.aichat.config.SecurityConfiguration;
import com.github.vladsaraykin.aichat.rag.api.*;
import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.UserAccount;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentControllerTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, RagExceptionHandler.class, MultipartExceptionHandler.class})
    static class Config {
        @Bean UserRepository userRepository() {
            var users = mock(UserRepository.class);
            when(users.account("alice")).thenReturn(Optional.of(new UserAccount("alice",
                    new BCryptPasswordEncoder().encode("test-password"), Instant.now())));
            return users;
        }
        @Bean DocumentService service() { return mock(DocumentService.class); }
        @Bean DocumentController documents(DocumentService service) { return new DocumentController(service); }
        @Bean ChunkingService chunkingService(DocumentService service) {
            var settings = new ChunkingSettings(384, 64);
            ChunkTokenEstimator estimator = String::length;
            return new ChunkingService(service, new DocumentChunker(estimator, settings), estimator, settings);
        }
        @Bean ChunkingController chunkingController(ChunkingService service) { return new ChunkingController(service); }
        @Bean IndexRepository indexes() { return mock(IndexRepository.class); }
        @Bean(destroyMethod = "close") IndexingService indexing(DocumentService service, IndexRepository indexes) {
            var settings = new ChunkingSettings(384, 64);
            return new IndexingService(service, new DocumentChunker(String::length, settings), settings, indexes,
                    text -> new EmbeddingModel.Result(new float[768], 1L), "embeddinggemma");
        }
        @Bean IndexController indexController(IndexingService service) { return new IndexController(service); }
    }

    @Test void indexEndpointsAuthenticateScopeOwnerAndSanitizeErrors() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var documents = context.getBean(DocumentService.class);
            var indexes = context.getBean(IndexRepository.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            String auth = "Basic " + Base64.getEncoder().encodeToString("alice:test-password".getBytes());
            UUID run = UUID.randomUUID();
            UUID document = UUID.randomUUID();
            mvc.perform(get("/api/rag/indexes/" + run)).andExpect(status().isUnauthorized());
            when(indexes.get("alice", run)).thenReturn(new IndexRun(run, document, DocumentChunk.Strategy.FIXED_SIZE,
                    "COMPLETED", "embeddinggemma", 768, 1, 1, 5L, 10L, null, Instant.now(), Instant.now()));
            mvc.perform(get("/api/rag/indexes/" + run).param("owner", "bob").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.embeddedChunks").value(1));
            verify(indexes, never()).get("bob", run);
            mvc.perform(get("/api/rag/indexes/" + run + "/chunks?limit=101").header("Authorization", auth))
                    .andExpect(status().isBadRequest());
            when(indexes.get("alice", run)).thenThrow(new RagFailure(RagFailure.Kind.NOT_FOUND, "Индекс не найден."));
            mvc.perform(get("/api/rag/indexes/" + run).header("Authorization", auth)).andExpect(status().isNotFound());
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret connection data"))
                    .when(indexes).get("alice", run);
            mvc.perform(get("/api/rag/indexes/" + run).header("Authorization", auth))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("STORAGE"));
            mvc.perform(post("/api/rag/documents/" + document + "/indexes").header("Authorization", auth)
                    .contentType("application/json").content("{\"strategy\":\"OTHER\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/rag/documents/" + document + "/indexes").header("Authorization", auth)
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
            var value = new RagDocument(document, "alice", "file.pdf", "application/pdf", UUID.randomUUID(), 5,
                    "a".repeat(64), new ExtractedText("Text", new ExtractedText.Metadata("pdf", 1, List.of(), List.of())), Instant.now());
            when(documents.get("alice", document)).thenReturn(value);
            when(indexes.create(eq(value), any(), any(), any())).thenReturn(run);
            doReturn(new IndexRun(run, document, DocumentChunk.Strategy.FIXED_SIZE, "PENDING", "embeddinggemma",
                    768, 0, 0, 0L, null, null, Instant.now(), null)).when(indexes).get("alice", run);
            mvc.perform(post("/api/rag/documents/" + document + "/indexes").header("Authorization", auth)
                    .contentType("application/json").content("{\"strategy\":\"FIXED_SIZE\"}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(run.toString()));
        }
    }

    @Test void basicAuthUploadOwnerSafeDtoAndErrors() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var service = context.getBean(DocumentService.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            var file = new MockMultipartFile("file", "test.pdf", "application/pdf", "content".getBytes());
            String auth = "Basic " + Base64.getEncoder().encodeToString("alice:test-password".getBytes());
            mvc.perform(multipart("/api/rag/documents").file(file)).andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
            var document = new RagDocument(UUID.randomUUID(), "alice", "test.pdf", "application/pdf", UUID.randomUUID(), 7,
                    "a".repeat(64), new ExtractedText("Текст", new ExtractedText.Metadata("pdf", 1, List.of(), List.of())), Instant.now());
            when(service.upload(eq("alice"), eq("test.pdf"), any())).thenReturn(document);
            mvc.perform(multipart("/api/rag/documents").file(file).param("owner", "bob").header("Authorization", auth))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.text").value("Текст"))
                    .andExpect(jsonPath("$.document.id").value(document.id().toString()))
                    .andExpect(jsonPath("$.document.storageKey").doesNotExist())
                    .andExpect(jsonPath("$.document.owner").doesNotExist());
            when(service.get("alice", document.id())).thenThrow(new RagFailure(RagFailure.Kind.NOT_FOUND, "Документ не найден."));
            mvc.perform(get("/api/rag/documents/" + document.id()).header("Authorization", auth)).andExpect(status().isNotFound());
            mvc.perform(get("/api/rag/documents/not-a-uuid").header("Authorization", auth))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
            mvc.perform(multipart("/api/rag/documents").header("Authorization", auth)).andExpect(status().isBadRequest());
            mvc.perform(multipart("/api/rag/documents").file(file).file(file).header("Authorization", auth))
                    .andExpect(status().isBadRequest());
            when(service.list("alice", 20, 0)).thenReturn(List.of(document.summary()));
            mvc.perform(get("/api/rag/documents").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].filename").value("test.pdf"));
            String chunkingPath = "/api/rag/documents/" + document.id() + "/chunking";
            mvc.perform(get(chunkingPath)).andExpect(status().isUnauthorized());
            doReturn(document).when(service).get("alice", document.id());
            mvc.perform(get(chunkingPath).header("Authorization", auth)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.settings.maxEstimatedTokens").value(384))
                    .andExpect(jsonPath("$.results.length()").value(2));
            mvc.perform(get("/api/rag/documents/bad-id/chunking").header("Authorization", auth))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID"));
        }
    }
}
