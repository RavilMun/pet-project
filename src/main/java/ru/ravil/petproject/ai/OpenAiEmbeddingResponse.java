package ru.ravil.petproject.ai;

import java.util.List;

public record OpenAiEmbeddingResponse(
        List<OpenAiEmbeddingData> data,
        OpenAiUsage usage
) {
}
