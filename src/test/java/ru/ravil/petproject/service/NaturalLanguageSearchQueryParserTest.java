package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.ravil.petproject.domain.InboxItemType;

class NaturalLanguageSearchQueryParserTest {

    private final NaturalLanguageSearchQueryParser parser = new NaturalLanguageSearchQueryParser();

    @Test
    void parsesSearchByPrefix() {
        SearchQuery query = parser.parse("найди кресло");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("кресло");
    }

    @Test
    void parsesSearchQuestion() {
        SearchQuery query = parser.parse("что я сохранял про кресло?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("кресло");
    }

    @Test
    void parsesRecent() {
        assertThat(parser.parse("покажи последние").type()).isEqualTo(SearchQueryType.RECENT);
    }

    @Test
    void parsesMyRecordsAsRecent() {
        assertThat(parser.parse("мои записи").type()).isEqualTo(SearchQueryType.RECENT);
        assertThat(parser.parse("покажи мои записи").type()).isEqualTo(SearchQueryType.RECENT);
    }

    @Test
    void parsesToday() {
        SearchQuery query = parser.parse("что я сохранил сегодня");

        assertThat(query.type()).isEqualTo(SearchQueryType.TODAY);
        assertThat(query.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void parsesYesterday() {
        SearchQuery query = parser.parse("что я сохранял вчера");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isNull();
        assertThat(query.period()).isEqualTo(SearchPeriod.YESTERDAY);
    }

    @Test
    void parsesYesterdayWhenDateWordGoesBeforeVerb() {
        SearchQuery query = parser.parse("что я вчера сохранил?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isNull();
        assertThat(query.period()).isEqualTo(SearchPeriod.YESTERDAY);
    }

    @Test
    void parsesTodayWhenDateWordGoesBeforeVerb() {
        SearchQuery query = parser.parse("что я сегодня сохранил?");

        assertThat(query.type()).isEqualTo(SearchQueryType.TODAY);
        assertThat(query.text()).isNull();
        assertThat(query.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void parsesSemanticWatchSearch() {
        SearchQuery query = parser.parse("что я хотел посмотреть");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("хотел посмотреть");
        assertThat(query.itemTypes()).containsExactly(InboxItemType.MOVIE);
        assertThat(query.tags()).isEmpty();
        assertThat(query.period()).isEqualTo(SearchPeriod.ALL);
    }

    @Test
    void parsesSavedMoviesTodaySearch() {
        SearchQuery query = parser.parse("какие фильмы я сохранил сегодня");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("фильмы");
        assertThat(query.itemTypes()).containsExactly(InboxItemType.MOVIE);
        assertThat(query.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void parsesWatchYesterdaySearch() {
        SearchQuery query = parser.parse("что я хотел посмотреть вчера");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("хотел посмотреть");
        assertThat(query.itemTypes()).containsExactly(InboxItemType.MOVIE);
        assertThat(query.period()).isEqualTo(SearchPeriod.YESTERDAY);
    }

    @Test
    void parsesGenericQuestionSearch() {
        SearchQuery query = parser.parse("что купить?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("купить");
        assertThat(query.itemTypes()).containsExactly(InboxItemType.PURCHASE_RESEARCH);
    }

    @Test
    void parsesQuestionWithFillerWords() {
        SearchQuery query = parser.parse("когда мне к ортодонту?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("ортодонту");
        assertThat(query.itemTypes()).isEmpty();
    }

    @Test
    void parsesSavedNounPhraseSearch() {
        SearchQuery query = parser.parse("сегодняшние рецепты пельменей");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("рецепты пельменей");
        assertThat(query.period()).isEqualTo(SearchPeriod.TODAY);
    }

    @Test
    void parsesRecipeIngredientSearch() {
        SearchQuery query = parser.parse("покажи рецепты с фаршем");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("рецепты с фаршем");
        assertThat(query.itemTypes()).isEmpty();
    }

    @Test
    void parsesWhatToCookFromIngredientSearch() {
        SearchQuery query = parser.parse("что приготовить из картохи?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("приготовить из картохи");
        assertThat(query.itemTypes()).isEmpty();
    }

    @Test
    void parsesOtherCookingVerbsWithoutSpecialCases() {
        SearchQuery query = parser.parse("что сварить из картохи?");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("сварить из картохи");
        assertThat(query.itemTypes()).isEmpty();
    }

    @Test
    void parsesProjectIdeasSearch() {
        SearchQuery query = parser.parse("какие идеи я записывал про pet project");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("идеи про pet project");
        assertThat(query.itemTypes()).containsExactlyInAnyOrder(InboxItemType.IDEA, InboxItemType.PROJECT);
    }

    @Test
    void parsesBookArticleAndReminderCategories() {
        assertThat(parser.parse("мои книги").itemTypes()).containsExactly(InboxItemType.BOOK);
        assertThat(parser.parse("покажи статьи").itemTypes()).containsExactly(InboxItemType.ARTICLE);
        assertThat(parser.parse("какие у меня напоминания").itemTypes()).containsExactly(InboxItemType.REMINDER);
    }

    @Test
    void doesNotTreatTechnicalGuideQueryAsArticleCategory() {
        SearchQuery query = parser.parse("найди spring guides");

        assertThat(query.type()).isEqualTo(SearchQueryType.SEARCH);
        assertThat(query.text()).isEqualTo("spring guides");
        assertThat(query.itemTypes()).isEmpty();
    }

    @Test
    void returnsUnknownForCaptureText() {
        assertThat(parser.parse("хочу посмотреть фильм Мгла").type()).isEqualTo(SearchQueryType.UNKNOWN);
    }
}
