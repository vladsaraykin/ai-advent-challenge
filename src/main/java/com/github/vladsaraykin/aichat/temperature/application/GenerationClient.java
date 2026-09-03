package com.github.vladsaraykin.aichat.temperature.application;

public interface GenerationClient {
    ProviderGeneration generate(GenerationCommand command);
}
