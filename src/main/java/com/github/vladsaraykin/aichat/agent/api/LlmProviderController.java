package com.github.vladsaraykin.aichat.agent.api;

import com.github.vladsaraykin.aichat.agent.infrastructure.LocalLlmProperties;
import com.github.vladsaraykin.aichat.agent.infrastructure.RoutingConversationModel;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings.LlmProvider;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm/providers")
public class LlmProviderController {
    private final LocalLlmProperties local;
    private final RoutingConversationModel routing;
    public LlmProviderController(LocalLlmProperties local,RoutingConversationModel routing) {
        this.local=local;this.routing=routing;
    }
    public record Provider(LlmProvider id,String name,String model,boolean enabled,boolean toolsEnabled) { }
    @GetMapping public List<Provider> providers() {
        return List.of(new Provider(LlmProvider.OPENAI,"OpenAI",null,routing.cloudEnabled(),true),
                new Provider(LlmProvider.LOCAL_MLX,"Local MLX",local.model(),local.enabled(),local.toolsEnabled()));
    }
}
