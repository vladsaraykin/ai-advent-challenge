package com.github.vladsaraykin.aichat.modelcomparison.application;

import com.github.vladsaraykin.aichat.modelcomparison.domain.TokenUsage;

public record ProviderGeneration(String answer, TokenUsage usage) { }
