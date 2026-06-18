package ru.ravil.petproject.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.config.OpenAiProperties;
import ru.ravil.petproject.metrics.MetricsService;

@Configuration
public class OpenAiClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "openai", name = "enabled", havingValue = "true")
    OpenAiClient openAiClient(OpenAiProperties properties, MetricsService metricsService) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("openai.api-key must be configured when OpenAI integration is enabled");
        }
        if (!StringUtils.hasText(properties.model())) {
            throw new IllegalStateException("openai.model must be configured when OpenAI integration is enabled");
        }
        if (!StringUtils.hasText(properties.embeddingModel())) {
            throw new IllegalStateException("openai.embedding-model must be configured when OpenAI integration is enabled");
        }
        return new OpenAiClient(properties.apiKey(), properties.model(), properties.embeddingModel(), metricsService);
    }
}
