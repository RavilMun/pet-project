package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.repository.InboxItemRepository;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchPeriod;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TelegramSearchScenarioIntegrationTest {

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @Autowired
    private InboxItemSearchService inboxItemSearchService;

    private final CommandTelegramIntentDetector commandDetector = new CommandTelegramIntentDetector();
    private final RuleBasedTelegramIntentDetector ruleBasedDetector = new RuleBasedTelegramIntentDetector(
            new NaturalLanguageSearchQueryParser()
    );

    @BeforeEach
    void setUp() {
        inboxItemRepository.deleteAll();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime yesterday = now.minusDays(1);

        save("Сделать AI Inbox через Telegram", "AI Inbox через Telegram",
                "Идея личного inbox-бота в Telegram с AI-классификацией и поиском. Категория: идеи.",
                InboxItemType.IDEA, Set.of("идея", "идеи", "ai", "telegram", "проект"), now);
        save("Идея для pet project: гибридный поиск", "Гибридный поиск для pet project",
                "Идея для pet project: совместить PostgreSQL full-text search и embeddings.",
                InboxItemType.IDEA, Set.of("идея", "идеи", "pet project", "поиск"), now);
        save("Идея: бот для поиска вакансий", "Бот для поиска вакансий",
                "Идея сервиса, который ищет вакансии и помогает откликаться. Категория: идеи.",
                InboxItemType.IDEA, Set.of("идея", "идеи", "вакансии", "бот"), now);
        save("Рецепт пельменей: тесто и фарш", "Рецепт пельменей",
                "Тесто, фарш, лук, соль и перец для домашних пельменей.",
                InboxItemType.NOTE, Set.of("рецепт", "еда", "пельмени"), now);
        save("Рецепт картохи: запечь с чесноком", "Рецепт картохи",
                "Картоха, чеснок, масло, соль и перец.",
                InboxItemType.NOTE, Set.of("рецепт", "еда", "картоха"), now);
        save("Рецепт сырников: творог, яйцо, мука", "Рецепт сырников",
                "Быстрый завтрак из творога.",
                InboxItemType.NOTE, Set.of("рецепт", "еда", "завтрак"), now);
        save("Маше нравятся цветы", "Маше нравятся цветы",
                "Маша любит цветы и хорошие букеты.",
                InboxItemType.NOTE, Set.of("маша", "цветы", "предпочтения"), now);
        save("Маша любит танцевать", "Маша любит танцевать",
                "Маша любит танцевать и ходить на занятия.",
                InboxItemType.NOTE, Set.of("маша", "танцы", "личное"), now);
        save("Прочитать книгу Цветы для Элджернона", "Прочитать книгу Цветы для Элджернона",
                "Классическая книга, которую хочется прочитать.",
                InboxItemType.BOOK, Set.of("книга", "чтение", "цветы для элджернона"), now);
        save("Хочу посмотреть фильм Мгла", "Посмотреть фильм Мгла",
                "Пользователь хотел посмотреть фильм Мгла.",
                InboxItemType.MOVIE, Set.of("фильм", "фильмы", "кино", "мгла", "просмотр"), now);
        save("Хочу посмотреть сериал Severance", "Посмотреть сериал Severance",
                "Пользователь хотел посмотреть сериал Severance.",
                InboxItemType.MOVIE, Set.of("сериал", "фильмы", "кино", "просмотр"), yesterday);
        save("Купить эргономичное кресло для рабочего стола", "Купить эргономичное кресло",
                "Исследовать покупку удобного кресла для работы.",
                InboxItemType.PURCHASE_RESEARCH, Set.of("покупка", "покупки", "кресло", "работа"), now);
        save("Купить кофемолку для дома", "Купить кофемолку",
                "Подобрать кофемолку для домашнего кофе.",
                InboxItemType.PURCHASE_RESEARCH, Set.of("покупка", "покупки", "кофе", "дом"), yesterday);
        save("Ортодонт во вторник в 18:30", "Ортодонт во вторник",
                "Запланирован визит к ортодонту во вторник в 18:30.",
                InboxItemType.REMINDER, Set.of("ортодонт", "врач", "напоминание"), now);
        save("Напомнить оплатить интернет завтра", "Оплатить интернет завтра",
                "Нужно оплатить домашний интернет завтра.",
                InboxItemType.REMINDER, Set.of("напоминание", "интернет", "оплата"), now);
        save("Запланировано: созвон по проекту в пятницу", "Созвон по проекту в пятницу",
                "В пятницу запланирован созвон по проекту.",
                InboxItemType.REMINDER, Set.of("план", "проект", "созвон"), now);
        save("Прочитать Clean Code", "Прочитать Clean Code",
                "Хочу прочитать книгу Clean Code.",
                InboxItemType.BOOK, Set.of("книга", "почитать", "clean code"), yesterday);
        save("Статья про PostgreSQL full-text search", "PostgreSQL full-text search",
                "Сохранена статья про полнотекстовый поиск PostgreSQL.",
                InboxItemType.ARTICLE, Set.of("статья", "postgresql", "поиск"), now);
        save("Разобраться с ошибкой docker compose", "Ошибка docker compose",
                "Нужно разобраться с ошибкой запуска docker compose.",
                InboxItemType.TASK, Set.of("docker", "debug", "задача"), now);
        save("Финансы: проверить расходы за месяц", "Проверить расходы",
                "Проверить расходы и бюджет за месяц.",
                InboxItemType.FINANCE, Set.of("финансы", "расходы", "бюджет"), yesterday);
        save("Health: купить витамин D", "Купить витамин D",
                "Купить витамин D для здоровья.",
                InboxItemType.HEALTH, Set.of("здоровье", "витамины", "покупка"), now);
        save("Проект: добавить AI fallback для поиска", "AI fallback для поиска",
                "Добавить AI fallback только для неоднозначных поисковых вопросов.",
                InboxItemType.PROJECT, Set.of("проект", "ai", "поиск"), now);
        save("Ссылка: https://spring.io/guides", "Spring Guides",
                "Сохранена ссылка на Spring Guides.",
                InboxItemType.LINK, Set.of("spring", "документация", "ссылка"), yesterday);
        save("Какие фильмы я сохранял?", "Вопрос про сохраненные фильмы",
                "Старый вопрос, случайно сохраненный как заметка.",
                InboxItemType.QUESTION, Set.of("вопрос", "фильмы"), now);
        save("Заметка про кухню: выбрать смеситель", "Выбрать смеситель",
                "Нужно выбрать смеситель для кухни.",
                InboxItemType.NOTE, Set.of("кухня", "смеситель"), now);
        save("Идея: сделать семейный календарь", "Семейный календарь",
                "Идея календаря для семьи и бытовых планов.",
                InboxItemType.IDEA, Set.of("идея", "идеи", "календарь", "семья"), yesterday);
        save("Рецепт пасты с томатами", "Рецепт пасты",
                "Паста, томаты, чеснок и базилик.",
                InboxItemType.NOTE, Set.of("рецепт", "паста", "еда"), yesterday);
        save("Напоминание: забрать заказ из пункта выдачи", "Забрать заказ",
                "Забрать заказ из пункта выдачи вечером.",
                InboxItemType.REMINDER, Set.of("напоминание", "заказ"), yesterday);
        save("Посмотреть доклад про Kafka", "Доклад про Kafka",
                "Хочу посмотреть технический доклад про Kafka.",
                InboxItemType.LEARNING, Set.of("доклад", "kafka", "посмотреть"), now);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("scenarios")
    void searchScenariosReturnExpectedItems(String userText, List<String> expectedTexts, List<String> forbiddenTexts) {
        TelegramIntent intent = detect(userText);

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);

        List<String> results = inboxItemSearchService.search(intent.query(), intent.itemTypes(), intent.tags(), intent.period(), 10)
                .stream()
                .map(InboxItemResponse::rawText)
                .toList();

        assertThat(results).containsAll(expectedTexts);
        if (!forbiddenTexts.isEmpty()) {
            assertThat(results).doesNotContainAnyElementsOf(forbiddenTexts);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> scenarios() {
        return Stream.of(
                scenario("мои идеи", List.of("Сделать AI Inbox через Telegram", "Идея: бот для поиска вакансий"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("Какие идеи я записывал?", List.of("Сделать AI Inbox через Telegram", "Идея: бот для поиска вакансий"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("сохраненные рецепты", List.of("Рецепт пельменей: тесто и фарш", "Рецепт сырников: творог, яйцо, мука"), List.of("Сделать AI Inbox через Telegram")),
                scenario("сегодняшние рецепты пельменей", List.of("Рецепт пельменей: тесто и фарш"), List.of("Рецепт пасты с томатами")),
                scenario("покажи сохраненный рецепт пельменей", List.of("Рецепт пельменей: тесто и фарш"), List.of("Рецепт сырников: творог, яйцо, мука")),
                scenario("что я хотел посмотреть?", List.of("Хочу посмотреть фильм Мгла", "Хочу посмотреть сериал Severance"), List.of("Какие фильмы я сохранял?")),
                scenario("какие фильмы я сохранял?", List.of("Хочу посмотреть фильм Мгла", "Хочу посмотреть сериал Severance"), List.of()),
                scenario("найди элджернона", List.of("Прочитать книгу Цветы для Элджернона"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("что мне купить?", List.of("Купить эргономичное кресло для рабочего стола", "Купить кофемолку для дома"), List.of("Какие фильмы я сохранял?")),
                scenario("покажи покупки", List.of("Купить эргономичное кресло для рабочего стола", "Купить кофемолку для дома"), List.of()),
                scenario("когда мне к ортодонту?", List.of("Ортодонт во вторник в 18:30"), List.of("Напомнить оплатить интернет завтра")),
                scenario("что Маша любит?", List.of("Маша любит танцевать"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("что у меня запланировано?", List.of("Запланировано: созвон по проекту в пятницу"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("какие у меня напоминания?", List.of("Напомнить оплатить интернет завтра", "Напоминание: забрать заказ из пункта выдачи"), List.of("Рецепт сырников: творог, яйцо, мука")),
                scenario("найди кресло", List.of("Купить эргономичное кресло для рабочего стола"), List.of("Купить кофемолку для дома")),
                scenario("поищи кофемолку", List.of("Купить кофемолку для дома"), List.of("Купить эргономичное кресло для рабочего стола")),
                scenario("что я сохранял про full text search?", List.of("Статья про PostgreSQL full-text search"), List.of("Рецепт пасты с томатами")),
                scenario("покажи заметки про кухню", List.of("Заметка про кухню: выбрать смеситель"), List.of("Ортодонт во вторник в 18:30")),
                scenario("мои книги", List.of("Прочитать Clean Code"), List.of("Хочу посмотреть фильм Мгла")),
                scenario("покажи статьи", List.of("Статья про PostgreSQL full-text search"), List.of("Прочитать Clean Code")),
                scenario("найди docker compose", List.of("Разобраться с ошибкой docker compose"), List.of("Ссылка: https://spring.io/guides")),
                scenario("мои финансы", List.of("Финансы: проверить расходы за месяц"), List.of("Health: купить витамин D")),
                scenario("что по здоровью?", List.of("Health: купить витамин D"), List.of("Финансы: проверить расходы за месяц")),
                scenario("покажи проекты", List.of("Проект: добавить AI fallback для поиска"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("найди spring guides", List.of("Ссылка: https://spring.io/guides"), List.of("Разобраться с ошибкой docker compose")),
                scenario("покажи семейный календарь", List.of("Идея: сделать семейный календарь"), List.of("Сделать AI Inbox через Telegram")),
                scenario("найди kafka", List.of("Посмотреть доклад про Kafka"), List.of("Статья про PostgreSQL full-text search")),
                scenario("что изучить про kafka?", List.of("Посмотреть доклад про Kafka"), List.of("Статья про PostgreSQL full-text search")),
                scenario("что у меня по docker?", List.of("Разобраться с ошибкой docker compose"), List.of("Ссылка: https://spring.io/guides")),
                scenario("что я сохранял вчера?", List.of("Рецепт пасты с томатами", "Прочитать Clean Code"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("что я вчера сохранил?", List.of("Рецепт пасты с томатами", "Прочитать Clean Code"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("какие фильмы я сохранил сегодня", List.of("Хочу посмотреть фильм Мгла"), List.of("Хочу посмотреть сериал Severance")),
                scenario("что я хотел посмотреть вчера", List.of("Хочу посмотреть сериал Severance"), List.of("Хочу посмотреть фильм Мгла")),
                scenario("что почитать?", List.of("Прочитать Clean Code"), List.of("Хочу посмотреть фильм Мгла")),
                scenario("что я хотел почитать?", List.of("Прочитать Clean Code"), List.of("Хочу посмотреть фильм Мгла")),
                scenario("покажи рецепты с фаршем", List.of("Рецепт пельменей: тесто и фарш"), List.of("Рецепт сырников: творог, яйцо, мука")),
                scenario("что приготовить из картохи?", List.of("Рецепт картохи: запечь с чесноком"), List.of("Рецепт сырников: творог, яйцо, мука")),
                scenario("что сварить из картохи?", List.of("Рецепт картохи: запечь с чесноком"), List.of("Рецепт сырников: творог, яйцо, мука")),
                scenario("какие идеи я записывал про pet project", List.of("Идея для pet project: гибридный поиск"), List.of("Рецепт пельменей: тесто и фарш")),
                scenario("что я сохранял про Kafka", List.of("Посмотреть доклад про Kafka"), List.of("Статья про PostgreSQL full-text search")),
                scenario("что было про расходы?", List.of("Финансы: проверить расходы за месяц"), List.of("Health: купить витамин D")),
                scenario("что было про кухню?", List.of("Заметка про кухню: выбрать смеситель"), List.of("Ортодонт во вторник в 18:30")),
                scenario("покажи напоминания про заказ", List.of("Напоминание: забрать заказ из пункта выдачи"), List.of("Рецепт сырников: творог, яйцо, мука"))
        );
    }

    private static org.junit.jupiter.params.provider.Arguments scenario(
            String userText,
            List<String> expectedTexts,
            List<String> forbiddenTexts
    ) {
        return org.junit.jupiter.params.provider.Arguments.of(userText, expectedTexts, forbiddenTexts);
    }

    private TelegramIntent detect(String userText) {
        TelegramIntent commandIntent = commandDetector.detect(userText);
        if (!commandIntent.isUnknown()) {
            return commandIntent;
        }
        return ruleBasedDetector.detect(userText);
    }

    private void save(
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            OffsetDateTime createdAt
    ) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.TELEGRAM);
        item.setTitle(title);
        item.setSummary(summary);
        item.setType(type);
        item.setStatus(InboxItemStatus.PROCESSED);
        item.setPriority(InboxItemPriority.MEDIUM);
        item.setTags(tags);
        InboxItem saved = inboxItemRepository.saveAndFlush(item);
        saved.setCreatedAt(createdAt);
        saved.setUpdatedAt(createdAt);
        inboxItemRepository.saveAndFlush(saved);
    }
}
