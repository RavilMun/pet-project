package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class AiEmbeddingServiceTest {

    private final ObjectProvider<OpenAiClient> openAiClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final OpenAiClient openAiClient = org.mockito.Mockito.mock(OpenAiClient.class);
    private final AiEmbeddingService service = new AiEmbeddingService(openAiClientProvider);

    @Test
    void embedReturnsEmptyWhenClientIsUnavailable() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(null);

        Optional<EmbeddingResult> result = service.embed("hello");

        assertThat(result).isEmpty();
    }

    @Test
    void embedFormatsOpenAiVector() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.embed("Kafka notes")).thenReturn(List.of(0.1, -0.2, 3.0));
        when(openAiClient.embeddingModel()).thenReturn("text-embedding-3-small");

        Optional<EmbeddingResult> result = service.embed("Kafka notes");

        assertThat(result).isPresent();
        assertThat(result.get().pgVector()).isEqualTo("[0.1,-0.2,3]");
        assertThat(result.get().model()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void embedReturnsEmptyWhenOpenAiFails() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.embed("Kafka notes")).thenThrow(new IllegalStateException("boom"));

        Optional<EmbeddingResult> result = service.embed("Kafka notes");

        assertThat(result).isEmpty();
    }
}
