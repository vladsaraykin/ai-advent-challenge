package com.github.vladsaraykin.aichat.reasoning;

public enum ReasoningApproach {
    DIRECT("Прямой ответ"),
    STEP_BY_STEP("Пошаговое решение"),
    GENERATED_PROMPT("Сначала создать промпт"),
    EXPERT_PANEL("Группа экспертов");

    private final String displayName;

    ReasoningApproach(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
