package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import java.util.List;

public interface AgentCatalog {
    List<AgentDefinition> definitions();
    Agent get(String id);
}
