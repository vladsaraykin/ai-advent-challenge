package com.github.vladsaraykin.aichat.modelcomparison.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ModelComparisonRequest(
        @NotBlank(message = "Введите запрос") @Size(max = 12_000, message = "Запрос слишком длинный") String prompt,
        @Min(value = 64, message = "Минимум 64 токена")
        @Max(value = 32_768, message = "Максимум 32768 токенов") int maxTokens,
        @NotNull(message = "Выберите модели") @Size(min = 3, max = 3, message = "Выберите ровно три модели")
        List<@NotBlank String> models) { }
