package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.ExtractedText;
import java.nio.file.Path;

public interface DocumentExtractor {
    ExtractedText extract(Path file, String extension);
}
