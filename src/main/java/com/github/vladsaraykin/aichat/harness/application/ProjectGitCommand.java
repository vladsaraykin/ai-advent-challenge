package com.github.vladsaraykin.aichat.harness.application;

import java.util.Locale;
import java.util.Set;

/** Detects unambiguous requests for the current branch so they use the git MCP without RAG. */
public final class ProjectGitCommand {
    private ProjectGitCommand() { }

    public static boolean requestsCurrentBranch(String content, Set<String> selectedServers) {
        if (content == null || selectedServers == null || !selectedServers.contains("project-git")) return false;
        String normalized = content.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean branch = normalized.contains("ветк") || normalized.contains("branch");
        boolean current = normalized.contains("текущ") || normalized.contains("сейчас")
                || normalized.contains("current") || normalized.contains("активн")
                || normalized.contains("получи") || normalized.contains("получить");
        return branch && current;
    }
}
