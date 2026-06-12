package ru.ravil.petproject.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class OpenAiClient {

    private final RestClient restClient;
    private final String model;

    public OpenAiClient(String apiKey, String model) {
        this.model = model;

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

        OpenAiChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(OpenAiChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI response has no choices");
        }

        OpenAiChatMessage message = response.choices().getFirst().message();
        if (message == null || message.content() == null) {
            throw new IllegalStateException("OpenAI response has no message content");
        }

        return message.content();
    }
}
