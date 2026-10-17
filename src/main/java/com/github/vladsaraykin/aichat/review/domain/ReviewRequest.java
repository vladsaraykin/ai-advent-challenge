package com.github.vladsaraykin.aichat.review.domain;

import java.util.List;
import java.util.UUID;

/** A bounded, immutable PR snapshot supplied by the trusted workflow, never a checkout to execute. */
public record ReviewRequest(UUID requestId, String repository, int pullNumber, String baseSha,
                            String headSha, String title, List<ChangedFile> files) {
    /** patch is complete; content is optional supplementary context, possibly labelled partial and line-numbered. */
    public record ChangedFile(String path, String status, String patch, String content) { }
    public ReviewRequest {
        if (requestId == null || repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                || pullNumber < 1 || baseSha == null || !baseSha.matches("[a-f0-9]{40}")
                || headSha == null || !headSha.matches("[a-f0-9]{40}") || title == null || title.length() > 500
                || files == null || files.isEmpty() || files.size() > 40) throw invalid();
        files = List.copyOf(files);
        long size = 0;
        var names = new java.util.HashSet<String>();
        boolean hasPatch = false;
        for (var file : files) {
            if (file == null || file.path() == null || file.path().length() > 512
                    || file.path().isBlank() || file.path().startsWith("/") || file.path().contains("\\")
                    || file.path().chars().anyMatch(Character::isISOControl)
                    || java.util.Arrays.asList(file.path().split("/")).contains("..") || !names.add(file.path())
                    || !java.util.Set.of("added", "modified", "removed", "renamed", "copied", "changed", "unchanged").contains(java.util.Objects.toString(file.status(), ""))
                    || file.patch() == null || file.content() == null) throw invalid();
            size += (long) file.path().length() + file.patch().length() + file.content().length();
            hasPatch |= !file.patch().isBlank();
        }
        if (size > 120000 || !hasPatch) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("PR: до 40 файлов и 120000 символов diff + содержимого; нужны SHA и непустой diff.");
    }
}
