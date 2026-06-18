package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.ai.OpenAiClient;

class TelegramActionDetectorTest {

    private OpenAiClient openAiClient;
    private TelegramActionDetector detector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        openAiClient = Mockito.mock(OpenAiClient.class);
        ObjectProvider<OpenAiClient> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(openAiClient);
        detector = new TelegramActionDetector(provider, new ObjectMapper());
    }

    @Test
    void parsesCompleteAction() {
        when(openAiClient.classify(any(), any()))
                .thenReturn("{\"action\":\"COMPLETE\",\"target\":\"покупка стула\",\"param\":\"\"}");

        TelegramAction action = detector.detect("закрой задачу про покупку стула");

        assertThat(action.type()).isEqualTo(TelegramActionType.COMPLETE);
        assertThat(action.target()).isEqualTo("покупка стула");
    }

    @Test
    void parsesSnoozeWithDuration() {
        when(openAiClient.classify(any(), any()))
                .thenReturn("{\"action\":\"SNOOZE\",\"target\":\"оплата интернета\",\"param\":\"2h\"}");

        TelegramAction action = detector.detect("отложи оплату интернета на 2 часа");

        assertThat(action.type()).isEqualTo(TelegramActionType.SNOOZE);
        assertThat(action.param()).isEqualTo("2h");
    }

    @Test
    void skipsLlmForNonActionText() {
        TelegramAction action = detector.detect("что я покупал вчера?");

        assertThat(action.isActionable()).isFalse();
        verifyNoInteractions(openAiClient);
    }

    @Test
    void returnsNoneWhenModelSaysNone() {
        when(openAiClient.classify(any(), any()))
                .thenReturn("{\"action\":\"NONE\",\"target\":\"\",\"param\":\"\"}");

        assertThat(detector.detect("забудь уже про это").isActionable()).isFalse();
    }
}
