package com.github.vladsaraykin.aichat.harness.api;

import com.github.vladsaraykin.aichat.harness.infrastructure.KnowledgeStatsRepository;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/harness/knowledge")
public class KnowledgeStatsController {
    private final KnowledgeStatsRepository repository;
    public KnowledgeStatsController(KnowledgeStatsRepository repository) { this.repository = repository; }
    @GetMapping("/stats")
    public KnowledgeStatsRepository.Stats stats(Principal user) { return repository.get(user.getName()); }
}
