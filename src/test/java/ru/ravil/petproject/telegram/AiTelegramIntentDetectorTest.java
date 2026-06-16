package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void detectAnyCallsAiForRegularNote() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "intent": "CAPTURE",
                  "query": "",
                  "tags": [],
                  "period": "ALL"
                }
                """);

        TelegramIntent intent = detector.detectAny("вчера вечером читал статью про pgvector");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.CAPTURE);
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
    void parsesWhoQuestionAsSearchIntent() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "intent": "SEARCH",
                  "query": "дубовская",
                  "tags": [],
                  "period": "ALL"
                }
                """);

        TelegramIntent intent = detector.detect("Кто такая Дубовская?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("дубовская");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "че я покупал вчера",
            "чё я покупал вчера",
            "чо я покупал вчера",
            "шо я покупал вчера",
            "че я сохранял про отпуск",
            "чё я записывал про ремонт",
            "че там было про Kafka",
            "скок стоила мышка",
            "скок стоил монитор",
            "скок я потратил в DNS",
            "сколько стоила мышка",
            "сколько я потратил на продукты",
            "почему мне не понравились Северяне",
            "почему мне понравился Harvest",
            "почему я отменил тренировку",
            "почему перенесли встречу",
            "почему PostgreSQL тормозил",
            "зачем я покупал переходник",
            "зачем записывал идею про отчеты",
            "что мне нравится в кофе",
            "что мне не нравится в кофе",
            "что мне понравилось в ресторане",
            "что мне не понравилось в отеле",
            "что я думаю про pgvector",
            "что я думаю насчет новой IDE",
            "что я хотел почитать",
            "что я хотел посмотреть",
            "что я хотел купить для дома",
            "что я ел на обед",
            "что я делал вечером",
            "что я делал после работы",
            "что я обсуждал с Андреем",
            "что купил в Перекрестке",
            "что покупал в DNS",
            "что заказал на Ozon",
            "что вернул в МВидео",
            "с чем использовать pgvector",
            "с чем лучше использовать embeddings",
            "с кем встречался вчера",
            "с кем созванивался утром",
            "куда ездил в субботу",
            "куда переехал Игорь",
            "куда я хотел сходить",
            "откуда заказал клавиатуру",
            "откуда был кофе",
            "где брал кабель",
            "где купил кабель",
            "где работал сегодня",
            "где была тренировка",
            "где обедал вчера",
            "когда купил монитор",
            "когда встречался с Романом",
            "когда забронирован столик",
            "кто такая Дубовская",
            "кто backend-разработчик",
            "кто мне советовал книжку",
            "кого я видел на дне рождения",
            "кому хотел подарить книгу",
            "кем работает Алексей",
            "во что хотел поиграть"
    })
    void routesConversationalQuestionsToAiIntentDetection(String text) {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "intent": "SEARCH",
                  "query": "semantic query",
                  "tags": [],
                  "period": "ALL"
                }
                """);

        TelegramIntent intent = detector.detect(text);

        assertThat(detector.shouldUseAiForIntent(text)).isTrue();
        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "хочу посмотреть новый сериал",
            "купил кабель в DNS",
            "вчера купил монитор Dell",
            "сегодня утром пил кофе",
            "моя любимая IDE IntelliJ IDEA",
            "мой терапевт Иванова",
            "Дубовская это ортодонт",
            "Игорь работает в Ozon",
            "кофе из Эфиопии понравился",
            "не понравился ресторан Северяне",
            "для хорошего сна не пить кофе вечером",
            "идея проекта: недельное резюме",
            "проверить расходы на подписки",
            "забронировал столик на пятницу",
            "после работы зашел в магазин",
            "прочитал статью про PostgreSQL indexes",
            "понял что embeddings лучше с FTS"
    })
    void doesNotRoutePlainCaptureStatementsToAiSearchGate(String text) {
        assertThat(detector.shouldUseAiForIntent(text)).isFalse();
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
