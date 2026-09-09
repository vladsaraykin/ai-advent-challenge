package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.TokenCounter;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public final class JTokkitTokenCounter implements TokenCounter {
    private final Encoding o200k;
    private final Encoding cl100k;

    public JTokkitTokenCounter() {
        var registry = Encodings.newDefaultEncodingRegistry();
        o200k = registry.getEncoding(EncodingType.O200K_BASE);
        cl100k = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override public int count(String model, String text) {
        return encoding(model).countTokensOrdinary(text == null ? "" : text);
    }

    private Encoding encoding(String model) {
        String id = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("gpt-5") || id.startsWith("gpt-4.1") || id.startsWith("gpt-4o")
                || id.startsWith("gpt-4.5") || id.startsWith("o1")
                || id.startsWith("o3") || id.startsWith("o4-")) {
            return o200k;
        }
        return cl100k;
    }
}
