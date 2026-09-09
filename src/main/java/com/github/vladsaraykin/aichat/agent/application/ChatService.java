package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final AgentCatalog registry;
    private final ChatRepository repository;
    private final Semaphore calls = new Semaphore(4);
    private final Set<UUID> busyChats = ConcurrentHashMap.newKeySet();

    public ChatService(AgentCatalog registry, ChatRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }
    public List<AgentDefinition> agents() { return registry.definitions(); }
    public List<Chat> list(String agentId) {
        registry.get(agentId);
        return repository.list(agentId);
    }
    public Chat create(String agentId) {
        registry.get(agentId);
        Chat chat = Chat.create(agentId);
        repository.save(chat);
        log.info("chat_created agentId={} chatId={}", agentId, chat.id());
        return chat;
    }
    public Chat get(String agentId, UUID chatId) {
        registry.get(agentId);
        return repository.get(agentId, chatId);
    }
    public Chat send(String agentId, UUID chatId, UUID messageId, String content) {
        Agent agent = registry.get(agentId);
        if (!busyChats.add(chatId)) throw new ChatFailure(ChatFailure.Kind.BUSY, "В этом чате уже ожидается ответ. Повторите позже.");
        boolean acquired = false;
        try {
            Chat chat = repository.get(agentId, chatId);
            var duplicate = chat.messages().stream().filter(message -> message.id().equals(messageId)).findFirst();
            if (duplicate.isPresent()) {
                if (duplicate.get().role() != ChatMessage.Role.USER || !duplicate.get().content().equals(content)) {
                    throw new ChatFailure(ChatFailure.Kind.INVALID, "Идентификатор сообщения уже использован");
                }
                return chat;
            }
            if (!(acquired = calls.tryAcquire())) {
                throw new ChatFailure(ChatFailure.Kind.BUSY, "Агенты заняты. Повторите отправку через несколько секунд.");
            }
            MDC.put("requestId", messageId.toString());
            MDC.put("chatId", chatId.toString());
            log.info("agent_request agentId={} chatId={} requestId={} historyMessages={} promptLength={}",
                    agentId, chatId, messageId, chat.messages().size(), content.length());
            var user = new ChatMessage(messageId, ChatMessage.Role.USER, content, Instant.now(), null);
            ChatMessage answer = agent.answer(chat.messages(), user);
            Chat updated = chat.append(user, answer);
            repository.save(updated);
            log.info("agent_response agentId={} chatId={} requestId={} totalTokens={} costUsd={}",
                    agentId, chatId, messageId, answer.metrics().totalTokens(), answer.metrics().totalCostUsd());
            return updated;
        } catch (ChatFailure exception) {
            log.warn("agent_request_failed agentId={} chatId={} requestId={} kind={}",
                    agentId, chatId, messageId, exception.kind());
            throw exception;
        } finally {
            MDC.remove("requestId");
            MDC.remove("chatId");
            if (acquired) calls.release();
            busyChats.remove(chatId);
        }
    }
}
