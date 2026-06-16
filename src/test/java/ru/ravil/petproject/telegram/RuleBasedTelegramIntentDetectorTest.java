package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchPeriod;

class RuleBasedTelegramIntentDetectorTest {

    private final RuleBasedTelegramIntentDetector detector = new RuleBasedTelegramIntentDetector(
            new NaturalLanguageSearchQueryParser()
    );

    @Test
    void detectSearchByPrefix() {
        TelegramIntent intent = detector.detect("найди кресло");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("кресло");
    }

    @Test
    void detectSearchQuestion() {
        TelegramIntent intent = detector.detect("что я сохранял про кресло?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("кресло");
    }

    @Test
    void detectRecent() {
        assertThat(detector.detect("покажи последние").type()).isEqualTo(TelegramIntentType.RECENT);
    }

    @Test
    void detectToday() {
        assertThat(detector.detect("что я сохранил сегодня").type()).isEqualTo(TelegramIntentType.TODAY);
    }

    @Test
    void detectYesterday() {
        TelegramIntent intent = detector.detect("что я сохранял вчера");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.period()).isEqualTo(SearchPeriod.YESTERDAY);
    }

    @Test
    void detectSemanticWatchSearch() {
        TelegramIntent intent = detector.detect("что я хотел посмотреть");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("хотел посмотреть");
        assertThat(intent.tags()).isEmpty();
        assertThat(intent.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void detectSemanticWatchSearchForToday() {
        TelegramIntent intent = detector.detect("что я хотел посмотреть сегодня");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("хотел посмотреть");
        assertThat(intent.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void detectSavedMoviesTodaySearch() {
        TelegramIntent intent = detector.detect("какие фильмы я сохранил сегодня");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("фильмы");
        assertThat(intent.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void detectWatchYesterdaySearch() {
        TelegramIntent intent = detector.detect("что я хотел посмотреть вчера");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("хотел посмотреть");
        assertThat(intent.period()).isEqualTo(SearchPeriod.YESTERDAY);
    }

    @Test
    void detectGenericQuestionSearch() {
        TelegramIntent intent = detector.detect("что купить?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("купить");
        assertThat(intent.tags()).isEmpty();
        assertThat(intent.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void detectWhatShouldIBuySearch() {
        TelegramIntent intent = detector.detect("что мне купить?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("купить");
    }

    @Test
    void detectSavedMoviesQuestionSearch() {
        TelegramIntent intent = detector.detect("какие фильмы я сохранял?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("фильмы");
    }

    @Test
    void detectRecordedIdeasQuestionSearch() {
        TelegramIntent intent = detector.detect("Какие идеи я записывал?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("идеи");
    }

    @Test
    void detectOrthodontistQuestionSearch() {
        TelegramIntent intent = detector.detect("когда мне к ортодонту?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("ортодонту");
    }

    @Test
    void detectPlannedItemsQuestionSearch() {
        TelegramIntent intent = detector.detect("Что у меня запланировано?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("запланировано");
    }

    @Test
    void detectRemindersQuestionSearch() {
        TelegramIntent intent = detector.detect("Какие у меня напоминания?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("напоминания");
    }

    @Test
    void detectGenericShowSearch() {
        TelegramIntent intent = detector.detect("покажи покупки");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("покупки");
        assertThat(intent.tags()).isEmpty();
    }

    @Test
    void detectSavedItemsNounPhraseSearch() {
        TelegramIntent intent = detector.detect("сохраненные рецепты");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("рецепты");
        assertThat(intent.tags()).isEmpty();
    }

    @Test
    void detectTodayNounPhraseSearch() {
        TelegramIntent intent = detector.detect("сегодняшние рецепты пельменей");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("рецепты пельменей");
        assertThat(intent.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void detectRecipeIngredientSearch() {
        TelegramIntent intent = detector.detect("покажи рецепты с фаршем");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("рецепты с фаршем");
        assertThat(intent.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void detectProjectIdeasSearch() {
        TelegramIntent intent = detector.detect("какие идеи я записывал про pet project");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("идеи про pet project");
        assertThat(intent.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void detectShowSavedSingleItemSearch() {
        TelegramIntent intent = detector.detect("покажи сохраненный рецепт пельменей");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("рецепт пельменей");
        assertThat(intent.tags()).isEmpty();
    }

    @Test
    void detectMyItemsNounPhraseSearch() {
        TelegramIntent intent = detector.detect("мои рецепты");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("рецепты");
        assertThat(intent.tags()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "че я покупал вчера",
            "чё я покупал вчера",
            "скок стоила мышка",
            "сколько стоил монитор",
            "почему мне не понравились Северяне",
            "что мне нравится в кофе",
            "что я думаю про pgvector",
            "с чем использовать pgvector",
            "с кем встречался вчера",
            "куда ездил в субботу",
            "откуда заказал клавиатуру",
            "где брал кабель",
            "кто мне советовал книжку",
            "про что была статья",
            "зачем я покупал переходник"
    })
    void detectConversationalQuestionSearch(String text) {
        TelegramIntent intent = detector.detect(text);

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
    }

    @Test
    void returnsUnknownForCaptureText() {
        assertThat(detector.detect("хочу посмотреть фильм Мгла").type()).isEqualTo(TelegramIntentType.UNKNOWN);
    }
}
