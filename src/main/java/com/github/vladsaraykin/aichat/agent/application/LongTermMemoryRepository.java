package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.LongTermMemory;
import java.util.UUID;

public interface LongTermMemoryRepository {
    LongTermMemory get(String agentId);
    LongTermMemory put(String agentId, long expectedVersion, LongTermMemory.Entry entry);
    LongTermMemory delete(String agentId, long expectedVersion, UUID entryId);
}
