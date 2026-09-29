package com.github.vladsaraykin.aichat.rag.application;

import java.util.function.Consumer;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Metrics;

public interface RagAnswerModel {
    record Result(String text, Metrics metrics) { }
    Result answer(String system, String user, Consumer<String> delta);
}
