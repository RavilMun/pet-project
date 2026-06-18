package ru.ravil.petproject.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage reported by OpenAI chat/embedding responses (used for token-spend metrics).
 * Embeddings populate {@code promptTokens}/{@code totalTokens} only.
 */
public record OpenAiUsage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens
) {
}
