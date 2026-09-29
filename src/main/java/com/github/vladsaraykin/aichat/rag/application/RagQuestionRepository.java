package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;

public interface RagQuestionRepository {
    List<RagQuestion.Source> search(String owner, UUID index, float[] vector, int topK);
    boolean create(String owner, RagQuestion question);
    void save(String owner, RagQuestion question);
    Optional<RagQuestion> find(String owner, UUID id);
    List<RagQuestion> list(String owner, UUID index);
    void recoverInterrupted();
}
