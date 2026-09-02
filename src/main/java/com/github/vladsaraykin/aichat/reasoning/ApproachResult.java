package com.github.vladsaraykin.aichat.reasoning;

import java.util.List;

public record ApproachResult(ReasoningApproach approach, List<ReasoningTurn> turns,
                             String answer, TokenUsage usage, long durationMillis) {
    public ApproachResult {
        turns = List.copyOf(turns);
    }

    public ApproachResult withDuration(long durationMillis) {
        return new ApproachResult(approach, turns, answer, usage, durationMillis);
    }

    public String durationLabel() {
        return DurationFormatter.format(durationMillis);
    }
}
