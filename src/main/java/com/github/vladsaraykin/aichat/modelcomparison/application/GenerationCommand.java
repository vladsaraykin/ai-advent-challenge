package com.github.vladsaraykin.aichat.modelcomparison.application;

public record GenerationCommand(String prompt, String model, int maxTokens) { }
