package com.github.vladsaraykin.aichat.movie;

public enum MovieResponseFormat {
    MARKDOWN("Markdown"),
    JSON("JSON");

    private final String displayName;

    MovieResponseFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
