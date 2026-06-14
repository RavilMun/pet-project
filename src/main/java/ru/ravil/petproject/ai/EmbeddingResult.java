package ru.ravil.petproject.ai;

public record EmbeddingResult(
        String pgVector,
        String model
) {
}
