package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.review.domain.*;
import com.github.vladsaraykin.aichat.rag.application.EmbeddingModel;
import com.github.vladsaraykin.aichat.rag.application.Reranker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

public class CodeReviewService {
    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);
    private final AgentDefinition definition;
    private final ConversationModel model;
    private final ReviewContextService context;
    private final ReviewRepository repository;
    private final String allowedRepository;
    private final Semaphore slot = new Semaphore(1);
    private final JsonMapper json = JsonMapper.builder().build();

    public CodeReviewService(AgentDefinition definition, ConversationModel model, ReviewContextService context,
                            ReviewRepository repository, String allowedRepository) {
        this.definition = definition; this.model = model; this.context = context; this.repository = repository;
        this.allowedRepository = allowedRepository;
    }
    public CodeReview review(String owner, ReviewRequest request) {
        if (!allowedRepository.equals(request.repository())) throw new ChatFailure(ChatFailure.Kind.INVALID, "Репозиторий не разрешён для ревью.");
        String sha = hash(json.writeValueAsString(request));
        var existing = repository.find(owner, request.requestId());
        if (existing.isPresent()) return repeat(existing.get(), sha);
        if (!slot.tryAcquire()) throw new ChatFailure(ChatFailure.Kind.BUSY, "Другое ревью ещё выполняется.");
        boolean began = false;
        try {
            if (!repository.begin(owner, request, sha)) return repeat(repository.find(owner, request.requestId()).orElseThrow(), sha);
            began = true;
            log.info("code_review_started requestId={} repository={} pr={} headSha={} fileCount={}",
                    request.requestId(), request.repository(), request.pullNumber(), request.headSha(), request.files().size());
            var sources = context.retrieve(owner, request);
            repository.sources(owner, request.requestId(), sources);
            String input = json.writeValueAsString(Map.of("pullRequest", Map.of("repository", request.repository(),
                    "number", request.pullNumber(), "baseSha", request.baseSha(), "headSha", request.headSha(), "title", request.title()),
                    "files", request.files(), "sources", sources));
            if (input.length() + definition.systemPrompt().length() > definition.maxHistoryChars())
                throw new IllegalStateException("review_context_limit");
            var reply = model.reviewCode(definition, List.of(new ChatMessage(request.requestId(), ChatMessage.Role.USER,
                    input, Instant.now(), null))).filter(part -> part.completed() != null)
                    .map(ConversationModel.StreamPart::completed).single().block(Duration.ofSeconds(definition.timeoutSeconds() + 5L));
            if (reply != null) repository.usage(owner, request.requestId(), reply.metrics());
            if (reply == null || reply.metrics() == null || !"stop".equals(reply.metrics().finishReason()))
                throw new IllegalStateException("review_incomplete");
            var findings = new ReviewValidator().validate(reply.text(), request, sources);
            boolean usageIncomplete = Boolean.FALSE.equals(reply.metrics().usageAvailable())
                    || (reply.metrics().promptTokens() == 0 && reply.metrics().completionTokens() == 0);
            String markdown = new ReviewRenderer().render(request, findings, sources, reply.metrics());
            if (usageIncomplete) markdown += "\nДанные расхода неполные; указанная сумма не является полным счётом провайдера.\n";
            var result = new CodeReview(request.requestId(), request.repository(), request.pullNumber(), request.baseSha(), request.headSha(),
                    findings, sources, reply.metrics(), usageIncomplete, markdown);
            repository.complete(owner, result);
            log.info("code_review_completed requestId={} findings={} sources={}", request.requestId(), findings.size(), sources.size());
            return result;
        } catch (RuntimeException error) {
            if (!began) throw error;
            String code = error instanceof Reranker.Failure ? "review_reranker_failed"
                    : error instanceof EmbeddingModel.Failure ? "review_embedding_failed"
                    : error instanceof IllegalStateException && Set.of("project_index_empty", "project_context_empty", "review_context_limit",
                            "review_incomplete", "review_validation_failed").contains(Objects.toString(error.getMessage(), ""))
                            ? error.getMessage() : "review_dependency_failed";
            try { repository.fail(owner, request.requestId(), code); }
            catch (RuntimeException storageError) { log.warn("code_review_failure_save_failed requestId={}", request.requestId()); }
            log.warn("code_review_failed requestId={} code={} errorType={}", request.requestId(), code, error.getClass().getName());
            throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Ревью не опубликовано: " + code + ". Проверьте RAG и модель; повтор запуска создаст новую попытку.");
        } finally { slot.release(); }
    }
    public ReviewRepository.Entry get(String owner, UUID id) {
        return repository.find(owner, id).orElseThrow(() -> new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Ревью не найдено."));
    }
    private CodeReview repeat(ReviewRepository.Entry entry, String sha) {
        if (!entry.payloadSha().equals(sha)) throw new ChatFailure(ChatFailure.Kind.INVALID, "UUID ревью уже использован для других данных.");
        if ("COMPLETED".equals(entry.status())) return entry.result();
        throw new ChatFailure(ChatFailure.Kind.BUSY, "Попытка ревью уже существует (" + entry.status() + "). Используйте новый requestId для новой попытки.");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
