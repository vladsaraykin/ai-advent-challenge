package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.LongTermMemory;
import java.util.UUID;

public interface LongTermMemoryRepository {
    String LEGACY_OWNER = "local";
    LongTermMemory get(String ownerId, String agentId);
    LongTermMemory put(String ownerId, String agentId, long expectedVersion, LongTermMemory.Entry entry);
    LongTermMemory delete(String ownerId, String agentId, long expectedVersion, UUID entryId);
    default LongTermMemory get(String agentId) { return get(LEGACY_OWNER, agentId); }
    default LongTermMemory put(String agentId, long version, LongTermMemory.Entry entry) {
        return put(LEGACY_OWNER, agentId, version, entry);
    }
    default LongTermMemory delete(String agentId, long version, UUID id) {
        return delete(LEGACY_OWNER, agentId, version, id);
    }
}
