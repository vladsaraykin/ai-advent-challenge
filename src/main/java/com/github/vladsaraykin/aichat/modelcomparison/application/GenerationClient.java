package com.github.vladsaraykin.aichat.modelcomparison.application;

public interface GenerationClient {
    ProviderGeneration generate(GenerationCommand command);
}
