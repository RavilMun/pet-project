package ru.ravil.petproject.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        boolean enabled,
        String apiKey,
        String model,
        String embeddingModel,
        boolean rerankEnabled
) {
}
