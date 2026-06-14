package ru.ravil.petproject.ai;

import java.util.List;

public record OpenAiEmbeddingData(
        List<Double> embedding
) {
}
