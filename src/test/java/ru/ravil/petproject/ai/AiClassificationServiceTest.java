package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemType;

@SuppressWarnings("unchecked")
class AiClassificationServiceTest {

    private final ObjectProvider<OpenAiClient> openAiClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final OpenAiClient openAiClient = org.mockito.Mockito.mock(OpenAiClient.class);
    private final AiClassificationService service = new AiClassificationService(openAiClientProvider, new ObjectMapper());

    @Test
    void classifyReturnsEmptyWhenClientIsUnavailable() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(null);

        Optional<AiClassificationResult> result = service.classify("hello");

        assertThat(result).isEmpty();
    }

    @Test
    void classifyParsesValidJsonResponse() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "title": "Choose a chair",
                  "summary": "Research ergonomic chairs.",
                  "type": "PURCHASE_RESEARCH",
                  "tags": ["furniture", "workplace"],
                  "priority": "HIGH",
                  "actionable": true
                }
                """);

        Optional<AiClassificationResult> result = service.classify("choose a chair");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Choose a chair");
        assertThat(result.get().type()).isEqualTo(InboxItemType.PURCHASE_RESEARCH);
        assertThat(result.get().priority()).isEqualTo(InboxItemPriority.HIGH);
        assertThat(result.get().tags()).containsExactly("furniture", "workplace");
        assertThat(result.get().actionable()).isTrue();
    }

    @Test
    void classifyParsesRussianMovieWishAsNonActionableMovie() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "title": "Посмотреть фильм Мгла",
                  "summary": "Пользователь хочет посмотреть фильм «Мгла».",
                  "type": "MOVIE",
                  "tags": ["фильм", "мгла", "просмотр"],
                  "priority": "LOW",
                  "actionable": false
                }
                """);

        Optional<AiClassificationResult> result = service.classify("хочу посмотреть фильм Мгла");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(InboxItemType.MOVIE);
        assertThat(result.get().priority()).isEqualTo(InboxItemPriority.LOW);
        assertThat(result.get().actionable()).isFalse();
        assertThat(result.get().tags()).containsExactly("фильм", "мгла", "просмотр");
    }

    @Test
    void classifyParsesReminderAsActionable() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "title": "Оплатить интернет завтра",
                  "summary": "Нужно оплатить домашний интернет завтра.",
                  "type": "REMINDER",
                  "tags": ["напоминание", "интернет", "оплата"],
                  "priority": "MEDIUM",
                  "actionable": true
                }
                """);

        Optional<AiClassificationResult> result = service.classify("напомни оплатить интернет завтра");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(InboxItemType.REMINDER);
        assertThat(result.get().actionable()).isTrue();
    }

    @Test
    void classifyUsesDefaultsForUnknownEnumValues() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "title": "Unknown",
                  "summary": "Unknown",
                  "type": "UNKNOWN_TYPE",
                  "tags": ["misc"],
                  "priority": "URGENT",
                  "actionable": false
                }
                """);

        Optional<AiClassificationResult> result = service.classify("unknown");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(InboxItemType.OTHER);
        assertThat(result.get().priority()).isEqualTo(InboxItemPriority.MEDIUM);
    }

    @Test
    void classifyReturnsEmptyWhenJsonIsInvalid() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("not json");

        Optional<AiClassificationResult> result = service.classify("hello");

        assertThat(result).isEmpty();
    }
}
