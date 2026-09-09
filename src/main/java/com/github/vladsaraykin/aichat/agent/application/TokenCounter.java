package com.github.vladsaraykin.aichat.agent.application;

public interface TokenCounter {
    int count(String model, String text);
}
