package com.github.vladsaraykin.aichat.temperature.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemperatureExperimentRequest(
        @NotBlank(message = "Введите запрос") @Size(max = 12_000, message = "Запрос слишком длинный") String prompt,
        @NotBlank(message = "Выберите модель") String model,
        @Min(value = 64, message = "Минимум 64 токена") @Max(value = 4096, message = "Максимум 4096 токенов") int maxTokens) { }
