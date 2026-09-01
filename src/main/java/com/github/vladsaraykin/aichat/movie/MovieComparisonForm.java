package com.github.vladsaraykin.aichat.movie;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class MovieComparisonForm {

    @NotBlank
    @Size(max = 200)
    private String model = "gpt-4.1-mini";

    @NotBlank
    @Size(max = 20_000)
    private String userPrompt;

    @NotNull
    private MovieResponseFormat responseFormat = MovieResponseFormat.MARKDOWN;

    @NotNull
    @Min(50)
    @Max(2_000)
    private Integer maxWords = 350;

    @NotBlank
    @Size(max = 80)
    private String stopSequence = "<END>";

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature = 0.7;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double topP = 1.0;

    @Min(0)
    @Max(100_000)
    private Integer topK;

    @NotNull
    @Min(64)
    @Max(16_000)
    private Integer maxTokens = 900;

    private Integer seed;

    @NotNull
    @DecimalMin("-2.0")
    @DecimalMax("2.0")
    private Double frequencyPenalty = 0.0;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
    public MovieResponseFormat getResponseFormat() { return responseFormat; }
    public void setResponseFormat(MovieResponseFormat responseFormat) { this.responseFormat = responseFormat; }
    public Integer getMaxWords() { return maxWords; }
    public void setMaxWords(Integer maxWords) { this.maxWords = maxWords; }
    public String getStopSequence() { return stopSequence; }
    public void setStopSequence(String stopSequence) { this.stopSequence = stopSequence; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getSeed() { return seed; }
    public void setSeed(Integer seed) { this.seed = seed; }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }
}
