package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.service.SearchPeriod;

@SuppressWarnings("unchecked")
class AiTelegramIntentDetectorTest {

    private final ObjectProvider<OpenAiClient> openAiClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final OpenAiClient openAiClient = org.mockito.Mockito.mock(OpenAiClient.class);
    private final AiTelegramIntentDetector detector = new AiTelegramIntentDetector(openAiClientProvider, new ObjectMapper());

    @Test
    void returnsUnknownWithoutCallingAiForRegularNote() {
        TelegramIntent intent = detector.detect("хочу посмотреть Мглу");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.UNKNOWN);
        verifyNoInteractions(openAiClientProvider);
    }

    @Test
    void returnsUnknownWhenClientIsUnavailable() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(null);

        TelegramIntent intent = detector.detect("а что там было про кухню?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.UNKNOWN);
    }

    @Test
    void parsesSearchIntent() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "intent": "SEARCH",
                  "query": "кухня",
                  "tags": ["ремонт"],
                  "period": "ALL"
                }
                """);

        TelegramIntent intent = detector.detect("а что там было про кухню?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("кухня");
        assertThat(intent.tags()).containsExactly("ремонт");
        assertThat(intent.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void parsesCaptureIntent() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "intent": "CAPTURE",
                  "query": "",
                  "tags": [],
                  "period": "ALL"
                }
                """);

        TelegramIntent intent = detector.detect("напомни оплатить интернет завтра");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.CAPTURE);
    }

    @Test
    void returnsUnknownForInvalidAiResponse() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("not json");

        TelegramIntent intent = detector.detect("а что там было про кухню?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.UNKNOWN);
    }
}
