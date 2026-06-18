package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class AiArticleSummaryServiceTest {

    private OpenAiClient openAiClient;
    private AiArticleSummaryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        openAiClient = Mockito.mock(OpenAiClient.class);
        ObjectProvider<OpenAiClient> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(openAiClient);
        service = new AiArticleSummaryService(provider, new ObjectMapper());
    }

    @Test
    void parsesTitleAndSummary() {
        when(openAiClient.classify(any(), any()))
                .thenReturn("{\"title\":\"pgvector в проде\",\"summary\":\"Как использовать pgvector для поиска.\"}");

        Optional<AiArticleSummaryService.ArticleSummary> result = service.summarize("длинный текст статьи про pgvector");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("pgvector в проде");
        assertThat(result.get().summary()).isEqualTo("Как использовать pgvector для поиска.");
    }

    @Test
    void returnsEmptyWhenSummaryBlank() {
        when(openAiClient.classify(any(), any())).thenReturn("{\"title\":\"x\",\"summary\":\"\"}");
        assertThat(service.summarize("текст")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertThat(service.summarize("  ")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyWhenOpenAiDisabled() {
        ObjectProvider<OpenAiClient> empty = Mockito.mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        AiArticleSummaryService disabled = new AiArticleSummaryService(empty, new ObjectMapper());

        assertThat(disabled.isAvailable()).isFalse();
        assertThat(disabled.summarize("текст")).isEmpty();
    }
}
