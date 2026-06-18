package ru.ravil.petproject.ai;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Optional wrapper around {@link OpenAiClient#transcribe} that degrades gracefully when the OpenAI
 * integration is disabled ({@code openai.enabled=false}) — mirrors {@link AiVisionService}. Turns a
 * voice message into text so the existing text-only search / Q&A pipeline can index it unchanged.
 */
@Service
public class AiTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiTranscriptionService.class);

    private final ObjectProvider<OpenAiClient> openAiClientProvider;

    public AiTranscriptionService(ObjectProvider<OpenAiClient> openAiClientProvider) {
        this.openAiClientProvider = openAiClientProvider;
    }

    public boolean isAvailable() {
        return openAiClientProvider.getIfAvailable() != null;
    }

    public Optional<String> transcribe(byte[] audio, String filename) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || audio == null || audio.length == 0) {
            return Optional.empty();
        }

        try {
            String text = openAiClient.transcribe(audio, filename);
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
        } catch (RuntimeException exception) {
            log.warn("AI transcription failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }
}
