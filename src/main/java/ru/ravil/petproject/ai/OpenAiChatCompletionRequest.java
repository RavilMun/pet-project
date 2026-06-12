package ru.ravil.petproject.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record OpenAiChatCompletionRequest(
        String model,
        List<OpenAiChatMessage> messages,
        @JsonProperty("response_format") Map<String, String> responseFormat,
        Double temperature
) {
}
