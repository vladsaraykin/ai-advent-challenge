package com.github.vladsaraykin.aichat.reasoning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReasoningComparisonForm {

    @NotBlank(message = "Введите задачу")
    @Size(max = 12_000, message = "Задача должна быть не длиннее 12000 символов")
    private String task = "";

    @NotBlank(message = "Укажите модель")
    @Size(max = 200, message = "Название модели должно быть не длиннее 200 символов")
    private String model = "gpt-4.1-mini";

    @NotNull(message = "Укажите лимит токенов")
    @Min(value = 128, message = "Минимум 128 токенов")
    @Max(value = 16_000, message = "Максимум 16000 токенов")
    private Integer maxTokens = 2_000;

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
}
