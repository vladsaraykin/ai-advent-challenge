package com.github.vladsaraykin.aichat.temperature.application;

import com.github.vladsaraykin.aichat.temperature.domain.TokenUsage;

public record ProviderGeneration(String answer, TokenUsage usage) { }
