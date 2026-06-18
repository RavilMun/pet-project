package ru.ravil.petproject.ai;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Optional wrapper around {@link OpenAiClient#describeImage} that degrades gracefully when the
 * OpenAI integration is disabled ({@code openai.enabled=false}) — mirrors {@link AiEmbeddingService}.
 * Turns an image into a textual description + OCR transcription so the existing text-only
 * search / Q&A pipeline can index it without any changes.
 */
@Service
public class AiVisionService {

    private static final Logger log = LoggerFactory.getLogger(AiVisionService.class);

    private final ObjectProvider<OpenAiClient> openAiClientProvider;

    public AiVisionService(ObjectProvider<OpenAiClient> openAiClientProvider) {
        this.openAiClientProvider = openAiClientProvider;
    }

    public boolean isAvailable() {
        return openAiClientProvider.getIfAvailable() != null;
    }

    public Optional<String> describe(String base64Image, String mimeType) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || !StringUtils.hasText(base64Image)) {
            return Optional.empty();
        }

        try {
            String description = openAiClient.describeImage(base64Image, mimeType);
            return StringUtils.hasText(description) ? Optional.of(description.trim()) : Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("AI image description failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }
}
