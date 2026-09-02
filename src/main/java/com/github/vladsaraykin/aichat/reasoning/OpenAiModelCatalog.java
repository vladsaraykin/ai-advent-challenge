package com.github.vladsaraykin.aichat.reasoning;

import java.util.List;

/** Models intentionally kept as a static catalog: the application never calls the Models API. */
public final class OpenAiModelCatalog {

    public static final List<ModelOption> CHAT_MODELS = List.of(
            new ModelOption("gpt-5.6-sol", "GPT-5.6 Sol"),
            new ModelOption("gpt-5.6-terra", "GPT-5.6 Terra"),
            new ModelOption("gpt-5.6-luna", "GPT-5.6 Luna"),
            new ModelOption("gpt-5.5", "GPT-5.5"),
            new ModelOption("gpt-5.4", "GPT-5.4"),
            new ModelOption("gpt-5.4-mini", "GPT-5.4 Mini"),
            new ModelOption("gpt-5.4-nano", "GPT-5.4 Nano"),
            new ModelOption("gpt-5.2", "GPT-5.2"),
            new ModelOption("gpt-5.1", "GPT-5.1"),
            new ModelOption("gpt-5", "GPT-5"),
            new ModelOption("gpt-5-mini", "GPT-5 Mini"),
            new ModelOption("gpt-5-nano", "GPT-5 Nano"),
            new ModelOption("o3", "o3"),
            new ModelOption("o3-pro", "o3 Pro"),
            new ModelOption("gpt-4.1", "GPT-4.1"),
            new ModelOption("gpt-4.1-mini", "GPT-4.1 Mini"),
            new ModelOption("gpt-4o", "GPT-4o"),
            new ModelOption("gpt-4o-mini", "GPT-4o Mini")
    );

    private OpenAiModelCatalog() {
    }

    public record ModelOption(String id, String displayName) {
    }
}
