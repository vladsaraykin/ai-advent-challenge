package com.github.vladsaraykin.aichat.rag.domain;

import java.util.List;

/** Offsets refer to UTF-16 characters in text, not bytes or tokens. */
public record ExtractedText(String text, Metadata metadata) {
    public record Metadata(String format, Integer pageCount, List<Block> blocks, List<String> warnings) {
        public Metadata { blocks = List.copyOf(blocks); warnings = List.copyOf(warnings); }
    }
    public record Block(String kind, String heading, Integer page, int start, int end) { }
}
