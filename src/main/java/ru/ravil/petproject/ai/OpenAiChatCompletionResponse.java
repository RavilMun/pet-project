package ru.ravil.petproject.ai;

import java.util.List;

public record OpenAiChatCompletionResponse(
        List<OpenAiChatCompletionChoice> choices,
        OpenAiUsage usage
) {
}
