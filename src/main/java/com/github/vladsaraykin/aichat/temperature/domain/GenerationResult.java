package com.github.vladsaraykin.aichat.temperature.domain;

public record GenerationResult(double temperature, Status status, String answer, String error,
                               TokenUsage usage, long durationMs) {
    public enum Status { SUCCESS, ERROR }

    public static GenerationResult success(double temperature, String answer, TokenUsage usage, long durationMs) {
        return new GenerationResult(temperature, Status.SUCCESS, answer, null, usage, durationMs);
    }

    public static GenerationResult failure(double temperature, String error, long durationMs) {
        return new GenerationResult(temperature, Status.ERROR, null, error, TokenUsage.EMPTY, durationMs);
    }
}
