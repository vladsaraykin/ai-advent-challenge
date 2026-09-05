package com.github.vladsaraykin.aichat.modelcomparison.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public record ModelOption(String model, String displayName,
                          BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {

    public static final List<ModelOption> CATALOG = List.of(
            option("gpt-4o-mini", "GPT-4o Mini", "0.15", "0.60"),
            option("gpt-4.1-mini", "GPT-4.1 Mini", "0.40", "1.60"),
            option("gpt-4.1", "GPT-4.1", "2.00", "8.00"),
            option("gpt-5-nano", "GPT-5 Nano", "0.05", "0.40"),
            option("gpt-5-mini", "GPT-5 Mini", "0.25", "2.00"),
            option("gpt-5", "GPT-5", "1.25", "10.00"),
            option("gpt-5.6-luna", "GPT-5.6 Luna", "0.20", "1.20"),
            option("gpt-5.6-terra", "GPT-5.6 Terra", "2.00", "12.00"),
            option("gpt-5.6-sol", "GPT-5.6 Sol", "4.00", "20.00")
    );

    public static Optional<ModelOption> find(String model) {
        return CATALOG.stream().filter(option -> option.model.equals(model)).findFirst();
    }

    private static ModelOption option(String model, String name, String input, String output) {
        return new ModelOption(model, name, new BigDecimal(input), new BigDecimal(output));
    }
}
