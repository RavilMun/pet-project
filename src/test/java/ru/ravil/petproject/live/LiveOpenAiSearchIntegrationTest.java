package ru.ravil.petproject.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.repository.InboxItemRepository;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.service.SearchPeriod;

@Tag("live-openai")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "openai.enabled=true",
        "telegram.bot.enabled=false"
})
class LiveOpenAiSearchIntegrationTest {

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @Autowired
    private InboxItemService inboxItemService;

    @Autowired
    private InboxItemSearchService inboxItemSearchService;

    @Autowired
    private InboxItemEmbeddingBackfillService embeddingBackfillService;

    @BeforeEach
    void setUp() {
        assumeTrue(LiveOpenAiTestSupport.isEnabled(), "RUN_LIVE_GPT_TESTS is not enabled");
        inboxItemRepository.deleteAll();
    }

    @Test
    void findsPersonPreferencesByNaturalLanguageQuestion() {
        seed(
                "Маше нравятся цветы",
                "Маше нравятся цветы",
                "Маша любит цветы и хорошие букеты.",
                InboxItemType.NOTE,
                Set.of("маша", "цветы", "предпочтения")
        );
        seed(
                "Маша любит танцевать",
                "Маша любит танцевать",
                "Маша любит танцевать и ходить на занятия.",
                InboxItemType.NOTE,
                Set.of("маша", "танцы", "личное")
        );
        seed(
                "Подарить Маше книгу или поездку на выходные",
                "Подарить Маше книгу или поездку на выходные",
                "Подарок для Маши: книга или поездка на выходные.",
                InboxItemType.IDEA,
                Set.of("подарок", "маше", "книга")
        );
        backfillAll();

        List<InboxItemResponse> results = inboxItemSearchService.search("что нравится Маше?", Set.of(), Set.of(), SearchPeriod.ALL, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).rawText()).isEqualTo("Маше нравятся цветы");
    }

    @Test
    void findsMovieWishBySemanticQuestion() {
        seed(
                "Хочу посмотреть сериал Severance",
                "Посмотреть сериал Severance",
                "Пользователь хотел посмотреть сериал Severance.",
                InboxItemType.MOVIE,
                Set.of("сериал", "просмотр")
        );
        seed(
                "Хочу посмотреть фильм Мгла",
                "Посмотреть фильм Мгла",
                "Пользователь хотел посмотреть фильм Мгла.",
                InboxItemType.MOVIE,
                Set.of("фильм", "мгла", "просмотр")
        );
        backfillAll();

        List<InboxItemResponse> results = inboxItemSearchService.search("что я хотел посмотреть?", Set.of(), Set.of(), SearchPeriod.ALL, 10);

        assertThat(results).isNotEmpty();
        assertThat(results)
                .extracting(InboxItemResponse::rawText)
                .contains("Хочу посмотреть фильм Мгла");
    }

    @Test
    void findsPurchaseResearchByGoalQuestion() {
        seed(
                "Купить узкую светлую обувницу в прихожую",
                "Купить узкую светлую обувницу в прихожую",
                "Нужна узкая светлая обувница в прихожую.",
                InboxItemType.PURCHASE_RESEARCH,
                Set.of("покупка", "обувница", "прихожая")
        );
        seed(
                "Посмотреть кресло для работы до 25000 рублей",
                "Посмотреть кресло для работы до 25000 рублей",
                "Подобрать кресло для работы до 25000 рублей.",
                InboxItemType.PURCHASE_RESEARCH,
                Set.of("покупка", "кресло", "работа")
        );
        backfillAll();

        List<InboxItemResponse> results = inboxItemSearchService.search("что купить для дома?", Set.of(), Set.of(), SearchPeriod.ALL, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).rawText()).isEqualTo("Купить узкую светлую обувницу в прихожую");
    }

    @Test
    void findsRecipeByIngredientQuestion() {
        seed(
                "Рецепт пельменей: тесто и фарш",
                "Рецепт пельменей",
                "Тесто, фарш, лук, соль и перец для домашних пельменей.",
                InboxItemType.NOTE,
                Set.of("рецепт", "еда", "пельмени")
        );
        seed(
                "Рецепт картохи: запечь с чесноком",
                "Рецепт картохи",
                "Картоха, чеснок, масло, соль и перец.",
                InboxItemType.NOTE,
                Set.of("рецепт", "еда", "картоха")
        );
        backfillAll();

        List<InboxItemResponse> results = inboxItemSearchService.search("что приготовить из картохи?", Set.of(), Set.of(), SearchPeriod.ALL, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).rawText()).isEqualTo("Рецепт картохи: запечь с чесноком");
    }

    @Test
    void findsNotesAboutProjectIdeasAndKafka() {
        seed(
                "Идея для pet project: гибридный поиск",
                "Гибридный поиск для pet project",
                "Идея для pet project: совместить PostgreSQL full-text search и embeddings.",
                InboxItemType.IDEA,
                Set.of("идея", "pet project", "поиск")
        );
        seed(
                "Посмотреть доклад про Kafka",
                "Доклад про Kafka",
                "Хочу посмотреть технический доклад про Kafka.",
                InboxItemType.LEARNING,
                Set.of("доклад", "kafka", "посмотреть")
        );
        backfillAll();

        List<InboxItemResponse> projectResults = inboxItemSearchService.search("какие идеи я записывал про pet project", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        List<InboxItemResponse> kafkaResults = inboxItemSearchService.search("что я сохранял про Kafka", Set.of(), Set.of(), SearchPeriod.ALL, 10);

        assertThat(projectResults).isNotEmpty();
        assertThat(projectResults.get(0).rawText()).isEqualTo("Идея для pet project: гибридный поиск");
        assertThat(kafkaResults).isNotEmpty();
        assertThat(kafkaResults.get(0).rawText()).isEqualTo("Посмотреть доклад про Kafka");
    }

    private void seed(
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags
    ) {
        inboxItemService.create(new CreateInboxItemRequest(
                rawText,
                title,
                summary,
                type,
                InboxItemSource.MANUAL,
                null,
                null,
                null,
                null,
                tags
        ));
    }

    private void backfillAll() {
        embeddingBackfillService.backfillMissingEmbeddings(100);
    }
}
