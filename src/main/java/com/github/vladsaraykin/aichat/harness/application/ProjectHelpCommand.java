package com.github.vladsaraykin.aichat.harness.application;

import java.util.Optional;

/** Parser for the chat's project-documentation slash command. */
public record ProjectHelpCommand(String question) {
    private static final String DEFAULT_QUESTION =
            "Кратко опиши назначение проекта, его основные компоненты и документацию.";

    public static Optional<ProjectHelpCommand> parse(String content) {
        if (content == null || !(content.equals("/help") || content.startsWith("/help ")
                || content.startsWith("/help\t") || content.startsWith("/help\n"))) return Optional.empty();
        String question = content.substring("/help".length()).strip();
        return Optional.of(new ProjectHelpCommand(question.isBlank() ? DEFAULT_QUESTION : question));
    }
}
