package ru.ravil.petproject.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 8_000L;

    private final RestClient restClient;
    private final String model;
    private final String embeddingModel;

    public OpenAiClient(String apiKey, String model, String embeddingModel) {
        this.model = model;
        this.embeddingModel = embeddingModel;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    public String classify(String systemPrompt, String userPrompt) {
        OpenAiChatCompletionRequest request = new OpenAiChatCompletionRequest(
                model,
                List.of(
                        new OpenAiChatMessage("system", systemPrompt),
                        new OpenAiChatMessage("user", userPrompt)
                ),
                Map.of("type", "json_object"),
                0.1
        );

        OpenAiChatCompletionResponse response = executeWithRetry("chat.completions", () -> restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(OpenAiChatCompletionResponse.class));

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI response has no choices");
        }

        OpenAiChatMessage message = response.choices().getFirst().message();
        if (message == null || message.content() == null) {
            throw new IllegalStateException("OpenAI response has no message content");
        }

        return message.content();
    }

    private static final String VISION_SYSTEM_PROMPT = """
            You describe images for a private personal memory assistant.
            Describe what is shown concisely but usefully for later search, and transcribe any visible text verbatim (OCR).
            Answer in the same language as any visible text, otherwise in Russian. Return plain text, no markdown.
            """;

    public String describeImage(String base64Image, String mimeType) {
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mimeType + ";base64," + base64Image)
        );
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", "Опиши изображение и распознай текст на нём."
        );
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", VISION_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(textPart, imagePart))
                ),
                "temperature", 0.2
        );

        OpenAiChatCompletionResponse response = executeWithRetry("vision", () -> restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(OpenAiChatCompletionResponse.class));

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI vision response has no choices");
        }
        OpenAiChatMessage message = response.choices().getFirst().message();
        if (message == null || message.content() == null) {
            throw new IllegalStateException("OpenAI vision response has no message content");
        }
        return message.content();
    }

    private static final String TRANSCRIPTION_MODEL = "whisper-1";

    /**
     * Transcribes audio bytes (e.g. a Telegram OGG/Opus voice message) to text via the Whisper API.
     * {@code filename} carries the extension Whisper uses for format detection (e.g. "voice.ogg").
     */
    public String transcribe(byte[] audio, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("model", TRANSCRIPTION_MODEL);

        OpenAiTranscriptionResponse response = executeWithRetry("audio.transcriptions", () -> restClient.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(OpenAiTranscriptionResponse.class));

        if (response == null || response.text() == null) {
            throw new IllegalStateException("OpenAI transcription response has no text");
        }
        return response.text();
    }

    public List<Double> embed(String input) {
        OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(embeddingModel, input);

        OpenAiEmbeddingResponse response = executeWithRetry("embeddings", () -> restClient.post()
                .uri("/embeddings")
                .body(request)
                .retrieve()
                .body(OpenAiEmbeddingResponse.class));

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("OpenAI embedding response has no data");
        }

        List<Double> embedding = response.data().getFirst().embedding();
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("OpenAI embedding response has no embedding");
        }

        return embedding;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    private <T> T executeWithRetry(String operation, Supplier<T> call) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException exception) {
                lastException = exception;
                int status = exception.getStatusCode().value();
                if (!isRetryableStatus(status) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long delay = retryAfterMillis(exception).orElse(backoffMillis(attempt));
                log.warn("OpenAI {} failed with HTTP {} (attempt {}/{}), retrying in {} ms",
                        operation, status, attempt, MAX_ATTEMPTS, delay);
                sleep(delay);
            } catch (ResourceAccessException exception) {
                lastException = exception;
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long delay = backoffMillis(attempt);
                log.warn("OpenAI {} I/O error '{}' (attempt {}/{}), retrying in {} ms",
                        operation, exception.getMessage(), attempt, MAX_ATTEMPTS, delay);
                sleep(delay);
            }
        }
        throw lastException;
    }

    static boolean isRetryableStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    static long backoffMillis(int attempt) {
        long exponential = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.max(0, attempt - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(0, 250);
        return exponential + jitter;
    }

    private static Optional<Long> retryAfterMillis(RestClientResponseException exception) {
        String header = exception.getResponseHeaders() == null
                ? null
                : exception.getResponseHeaders().getFirst("Retry-After");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? Optional.empty() : Optional.of(Math.min(MAX_BACKOFF_MS, seconds * 1000L));
        } catch (NumberFormatException exception2) {
            return Optional.empty();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before OpenAI retry", exception);
        }
    }
}
