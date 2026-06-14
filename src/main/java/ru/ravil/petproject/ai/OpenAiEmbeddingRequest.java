package ru.ravil.petproject.ai;

public record OpenAiEmbeddingRequest(
        String model,
        String input
) {
}
