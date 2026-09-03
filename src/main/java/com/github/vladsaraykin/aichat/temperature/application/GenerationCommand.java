package com.github.vladsaraykin.aichat.temperature.application;

public record GenerationCommand(String prompt, String model, int maxTokens, double temperature) { }
