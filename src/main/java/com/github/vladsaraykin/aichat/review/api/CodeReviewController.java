package com.github.vladsaraykin.aichat.review.api;

import com.github.vladsaraykin.aichat.review.application.*;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reviews")
@ConditionalOnProperty(prefix = "app", name = {"rag.enabled", "review.enabled"}, havingValue = "true", matchIfMissing = true)
public class CodeReviewController {
    private final CodeReviewService reviews;
    public CodeReviewController(CodeReviewService reviews) { this.reviews = reviews; }
    @PostMapping public CodeReview review(Principal principal, @RequestBody ReviewRequest request) {
        return reviews.review(principal.getName(), request);
    }
    @GetMapping("/{id}") public ReviewRepository.Entry get(Principal principal, @PathVariable UUID id) {
        return reviews.get(principal.getName(), id);
    }
}
