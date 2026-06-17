package ru.ravil.petproject.ai;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(AiEmbeddingService.class);

    private final ObjectProvider<OpenAiClient> openAiClientProvider;

    public AiEmbeddingService(ObjectProvider<OpenAiClient> openAiClientProvider) {
        this.openAiClientProvider = openAiClientProvider;
    }

    public Optional<String> currentModel() {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        return openAiClient == null ? Optional.empty() : Optional.ofNullable(openAiClient.embeddingModel());
    }

    public Optional<EmbeddingResult> embed(String text) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || !StringUtils.hasText(text)) {
            return Optional.empty();
        }

        try {
            List<Double> embedding = openAiClient.embed(text);
            return Optional.of(new EmbeddingResult(
                    VectorEmbeddingFormatter.toPgVector(embedding),
                    openAiClient.embeddingModel()
            ));
        } catch (RuntimeException exception) {
            log.warn("AI embedding failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }
}
