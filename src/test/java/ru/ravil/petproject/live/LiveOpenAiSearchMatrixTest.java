package ru.ravil.petproject.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.InboxItemRepository;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.SearchPeriod;

@Tag("live-openai")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "openai.enabled=true",
        "telegram.bot.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveOpenAiSearchMatrixTest {

    private static final int LIMIT = 10;

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @Autowired
    private InboxItemSearchService inboxItemSearchService;

    @Autowired
    private InboxItemEmbeddingBackfillService embeddingBackfillService;

    private OffsetDateTime referenceNow;

    @BeforeAll
    void seedCorpus() {
        assumeTrue(LiveOpenAiTestSupport.isEnabled(), "RUN_LIVE_GPT_TESTS is not enabled");
        inboxItemRepository.deleteAll();
        referenceNow = OffsetDateTime.now();

        for (ScenarioFamily family : families()) {
            seed(
                    family.rawText(),
                    family.title(),
                    family.summary(),
                    family.type(),
                    family.tags()
            );
        }

        for (ExtendedScenario scenario : extendedScenariosList()) {
            seed(
                    scenario.rawText(),
                    scenario.title(),
                    scenario.summary(),
                    scenario.type(),
                    scenario.tags(),
                    scenario.createdAt()
            );
        }

        embeddingBackfillService.backfillMissingEmbeddings(100);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("scenarios")
    void searchMatrix(String label, String query, String expectedFirstRawText) {
        List<MemoryUnitResponse> results = inboxItemSearchService.search(query, Set.of(), Set.of(), SearchPeriod.ALL, LIMIT);

        assertThat(results)
                .as(label)
                .isNotEmpty();
        assertThat(results)
                .as(label)
                .extracting(MemoryUnitResponse::sourceRawText)
                .contains(expectedFirstRawText);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("extendedScenarios")
    void extendedSearchMatrix(String label, String query, String expectedFirstRawText) {
        List<MemoryUnitResponse> results = inboxItemSearchService.search(query, Set.of(), Set.of(), SearchPeriod.ALL, LIMIT);

        assertThat(results)
                .as(label)
                .isNotEmpty();
        assertThat(results)
                .as(label)
                .extracting(MemoryUnitResponse::sourceRawText)
                .contains(expectedFirstRawText);
    }

    static Stream<Arguments> scenarios() {
        List<Arguments> arguments = new ArrayList<>();
        for (ScenarioFamily family : families()) {
            for (String query : family.queries()) {
                arguments.add(Arguments.of(family.label() + " :: " + query, query, family.rawText()));
            }
        }
        return arguments.stream();
    }

    Stream<Arguments> extendedScenarios() {
        return extendedScenariosList().stream()
                .map(scenario -> Arguments.of(scenario.label() + " :: " + scenario.query(), scenario.query(), scenario.rawText()));
    }

    private static List<ScenarioFamily> families() {
        return List.of(
                family(
                        "people_dislike",
                        "Саша не любит шумные бары",
                        "Саша не любит шумные бары",
                        "Саша раздражается от шумных баров и слишком громкой музыки.",
                        InboxItemType.NOTE,
                        Set.of("саша", "не любит", "бары", "шум"),
                        List.of(
                                "кто не любит шумные бары?",
                                "что Саша не любит?",
                                "чего Саша избегает?",
                                "кто не нравится Саше?",
                                "какие места Саша не любит?",
                                "где Саша не хочет бывать?",
                                "что про шумные бары?",
                                "что Саша терпеть не может?",
                                "покажи антипатии Саши",
                                "что у Саши вызывает раздражение?"
                        )
                ),
                family(
                        "people_like",
                        "Саша любит ранние прогулки",
                        "Саша любит ранние прогулки",
                        "Саша предпочитает гулять рано утром и в тишине.",
                        InboxItemType.NOTE,
                        Set.of("саша", "прогулки", "утро", "привычки"),
                        List.of(
                                "что любит Саша?",
                                "какие привычки у Саши?",
                                "чем Саша любит заниматься?",
                                "какие утренние привычки есть у Саши?",
                                "что Саша предпочитает утром?",
                                "как Саша любит гулять?",
                                "покажи предпочтения Саши",
                                "что нравится Саше по утрам?",
                                "какие прогулки любит Саша?",
                                "что у Саши любимое?"
                        )
                ),
                family(
                        "story_source",
                        "Андрюха рассказал мне историю про ночной поезд",
                        "Андрюха рассказал мне историю про ночной поезд",
                        "Андрюха рассказал мне историю про ночной поезд и задержку на станции.",
                        InboxItemType.NOTE,
                        Set.of("андрюха", "история", "поезд", "ночь"),
                        List.of(
                                "кто рассказал мне про ночной поезд?",
                                "что я слышал про Андрюху?",
                                "какая история была про ночной поезд?",
                                "кто рассказывал про Андрюху?",
                                "что мне говорил Андрюха?",
                                "откуда история про поезд?",
                                "что было сказано про ночной поезд?",
                                "кто упоминал задержку на станции?",
                                "что я узнал от Андрюхи?",
                                "покажи историю про ночной поезд"
                        )
                ),
                family(
                        "promise_buy",
                        "Купить роутер после зарплаты",
                        "Купить роутер после зарплаты",
                        "Нужно купить новый роутер после ближайшей зарплаты.",
                        InboxItemType.TASK,
                        Set.of("покупка", "роутер", "зарплата", "дом"),
                        List.of(
                                "что купить после зарплаты?",
                                "что я обещал купить?",
                                "что отложил до зарплаты?",
                                "покажи покупки после зарплаты",
                                "что мне нужно купить потом?",
                                "какую покупку я перенес?",
                                "что я собирался взять после зарплаты?",
                                "что надо купить для дома после зарплаты?",
                                "что у меня в покупках на потом?",
                                "чего я жду до зарплаты?"
                        )
                ),
                family(
                        "delay_tasks",
                        "Откладываю разбор налогов",
                        "Откладываю разбор налогов",
                        "Разбор налогов и документов пока отложен на потом.",
                        InboxItemType.TASK,
                        Set.of("налоги", "откладываю", "документы", "дела"),
                        List.of(
                                "что я откладываю?",
                                "что я отложил на потом?",
                                "какие дела у меня висят?",
                                "что с налогами?",
                                "что я не хочу делать сейчас?",
                                "что нужно добрать по документам?",
                                "покажи отложенные дела",
                                "что я тяну с налогами?",
                                "какую задачу я не закрыл?",
                                "что осталось разобрать из документов?"
                        )
                ),
                family(
                        "work_learning",
                        "Советовали изучить Terraform для работы",
                        "Советовали изучить Terraform для работы",
                        "Коллеги советовали изучить Terraform для рабочих задач.",
                        InboxItemType.LEARNING,
                        Set.of("terraform", "работа", "совет", "обучение"),
                        List.of(
                                "что мне советовали по работе?",
                                "что изучить для работы?",
                                "какой совет был по Terraform?",
                                "что я должен выучить для работы?",
                                "что мне рекомендовали изучить?",
                                "какой инструмент советовали посмотреть?",
                                "что по обучению для работы?",
                                "какие советы были про Terraform?",
                                "что я хочу изучить для работы?",
                                "что мне порекомендовали коллеги?"
                        )
                ),
                family(
                        "movie_watch",
                        "Хочу посмотреть сериал Разделение",
                        "Хочу посмотреть сериал Разделение",
                        "Хочу посмотреть сериал Разделение вечером.",
                        InboxItemType.MOVIE,
                        Set.of("сериал", "разделение", "просмотр", "вечер"),
                        List.of(
                                "что я хотел посмотреть?",
                                "какой сериал я хочу посмотреть?",
                                "что посмотреть вечером?",
                                "какие фильмы я сохранял?",
                                "что у меня в списке просмотра?",
                                "что мне хочется посмотреть сегодня?",
                                "покажи записи про просмотр",
                                "какой сериал я откладывал на вечер?",
                                "что я сохранял про сериалы?",
                                "что можно посмотреть вечером?"
                        )
                ),
                family(
                        "book_read",
                        "Хочу прочитать книгу Заводной апельсин",
                        "Хочу прочитать книгу Заводной апельсин",
                        "Хочу прочитать книгу Заводной апельсин и обсудить её позже.",
                        InboxItemType.BOOK,
                        Set.of("книга", "чтение", "заводной апельсин", "литература"),
                        List.of(
                                "что я хотел почитать?",
                                "какую книгу я хочу прочитать?",
                                "какую книгу я хотел прочитать?",
                                "какие книги я сохранял?",
                                "что у меня в списке чтения?",
                                "что мне почитать вечером?",
                                "покажи чтение",
                                "что я отложил почитать?",
                                "какую литературу я хотел открыть?",
                                "что я собирался прочитать?"
                        )
                ),
                family(
                        "food_try",
                        "Хочу попробовать том ям",
                        "Хочу попробовать том ям",
                        "Хочу попробовать том ям в хорошем месте рядом с домом.",
                        InboxItemType.IDEA,
                        Set.of("еда", "том ям", "ресторан", "пробовать"),
                        List.of(
                                "что попробовать поесть?",
                                "какую еду я хочу попробовать?",
                                "что я хотел попробовать из еды?",
                                "что я хотел попробовать из еды?",
                                "покажи еду, которую я хочу попробовать",
                                "что я сохранял про том ям?",
                                "какое блюдо мне интересно?",
                                "что из еды я откладывал попробовать?",
                                "что я хочу съесть в ресторане?",
                                "какую еду посмотреть?"
                        )
                ),
                family(
                        "purchase_chair",
                        "Нужен новый стул для рабочего места",
                        "Нужен новый стул для рабочего места",
                        "Нужен удобный новый стул для рабочего места дома.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("покупка", "стул", "работа", "дом"),
                        List.of(
                                "что купить для рабочего места?",
                                "какой стул мне нужен?",
                                "что я выбирал для работы?",
                                "покажи покупки для офиса",
                                "что мне нужно купить для дома и работы?",
                                "какой предмет для работы я искал?",
                                "что я смотрел из мебели?",
                                "что выбрать для рабочего места?",
                                "какую покупку для работы я сохранил?",
                                "что у меня в списке на рабочее место?"
                        )
                ),
                family(
                        "travel_plan",
                        "Поездка в Калининград на майские",
                        "Поездка в Калининград на майские",
                        "Поездка в Калининград на майские с билетами и жильем.",
                        InboxItemType.TASK,
                        Set.of("путешествие", "калининград", "майские", "билеты"),
                        List.of(
                                "что я планировал на майские?",
                                "куда я хотел поехать?",
                                "что по поездке в Калининград?",
                                "покажи планы на майские",
                                "что я искал для поездки?",
                                "какой город я собирался посетить?",
                                "что связано с путешествием?",
                                "что я хотел забронировать?",
                                "какая поездка у меня в планах?",
                                "что я хотел забронировать?"
                        )
                ),
                family(
                        "reminder_doctor",
                        "Напоминание позвонить врачу в четверг",
                        "Напоминание позвонить врачу в четверг",
                        "В четверг нужно позвонить врачу и уточнить запись.",
                        InboxItemType.REMINDER,
                        Set.of("напоминание", "врач", "четверг", "запись"),
                        List.of(
                                "что у меня в четверг?",
                                "когда мне позвонить врачу?",
                                "какое напоминание на четверг?",
                                "что мне надо сделать у врача?",
                                "покажи напоминания",
                                "что у меня запланировано на четверг?",
                                "что я должен не забыть?",
                                "когда запись к врачу?",
                                "какое дело связано с врачом?",
                                "что мне напомнить про врача?"
                        )
                ),
                family(
                        "idea_app_search",
                        "Идея для приложения заметок с поиском",
                        "Идея для приложения заметок с поиском",
                        "Идея приложения для заметок с хорошим поиском и AI.",
                        InboxItemType.IDEA,
                        Set.of("идея", "приложение", "заметки", "поиск"),
                        List.of(
                                "какие идеи я записывал?",
                                "что я думал про приложение?",
                                "покажи идеи про поиск",
                                "что я сохранял про заметки?",
                                "какую идею про приложение я хранил?",
                                "что связано с заметками и поиском?",
                                "что я придумывал для приложения?",
                                "какие идеи у меня были про AI?",
                                "покажи мои продуктовые идеи",
                                "что я хотел построить?"
                        )
                ),
                family(
                        "link_spring",
                        "Ссылка на документацию Spring Boot",
                        "Ссылка на документацию Spring Boot",
                        "Сохранена ссылка на официальную документацию Spring Boot.",
                        InboxItemType.LINK,
                        Set.of("ссылка", "spring boot", "документация", "java"),
                        List.of(
                                "что я сохранял про Spring Boot?",
                                "покажи ссылки по Java",
                                "какая у меня документация?",
                                "что я хотел почитать по Spring?",
                                "покажи документацию",
                                "что про Spring Boot я сохранял?",
                                "какую ссылку я оставлял?",
                                "что по официальной документации?",
                                "найди spring boot",
                                "что у меня есть по фреймворку Spring?"
                        )
                ),
                family(
                        "finance_subscriptions",
                        "Финансы: отменить лишние подписки",
                        "Финансы: отменить лишние подписки",
                        "Нужно отменить лишние подписки и проверить расходы.",
                        InboxItemType.FINANCE,
                        Set.of("финансы", "подписки", "расходы", "деньги"),
                        List.of(
                                "что я тратил на подписки?",
                                "покажи расходы",
                                "что по деньгам?",
                                "какие подписки я хотел отменить?",
                                "что у меня с финансами?",
                                "что я собирался проверить по расходам?",
                                "какие лишние траты есть?",
                                "что нужно отписать?",
                                "покажи финансовые заметки",
                                "что я откладывал по деньгам?"
                        )
                ),
                family(
                        "health_vitamin",
                        "Здоровье: купить витамин D",
                        "Здоровье: купить витамин D",
                        "Купить витамин D и не забыть обсудить это с врачом.",
                        InboxItemType.HEALTH,
                        Set.of("здоровье", "витамин d", "врач", "покупка"),
                        List.of(
                                "что по здоровью?",
                                "какие покупки для здоровья?",
                                "что мне купить из витаминов?",
                                "покажи здоровье",
                                "что я сохранял про витамин D?",
                                "какие дела у меня по здоровью?",
                                "что нужно взять после врача?",
                                "какую витаминную покупку я хотел?",
                                "что я должен купить для здоровья?",
                                "что у меня по витаминам?"
                        )
                ),
                family(
                        "telegram_bot_debug",
                        "Разобраться с Telegram ботом и поиском",
                        "Разобраться с Telegram ботом и поиском",
                        "Нужно понять, почему Telegram бот и поиск ведут себя странно.",
                        InboxItemType.TASK,
                        Set.of("telegram", "бот", "поиск", "отладка"),
                        List.of(
                                "что мне надо отладить?",
                                "покажи задачи по Telegram",
                                "что я хотел починить?",
                                "что не так с поиском?",
                                "какую проблему я разбирал?",
                                "что нужно исправить в боте?",
                                "покажи технические задачи",
                                "что я думал про Telegram?",
                                "какая у меня отладка?",
                                "что связано с поиском и ботом?"
                        )
                ),
                family(
                        "meeting_lisa",
                        "Вечером встреча с Лизой у набережной",
                        "Вечером встреча с Лизой у набережной",
                        "Вечером встреча с Лизой у набережной, возможно в кафе рядом.",
                        InboxItemType.REMINDER,
                        Set.of("встреча", "лиза", "набережная", "вечер"),
                        List.of(
                                "что у меня вечером?",
                                "с кем встреча у набережной?",
                                "когда встреча с Лизой?",
                                "что запланировано на вечер?",
                                "где я должен быть вечером?",
                                "какая встреча у меня есть?",
                                "что я помнил про Лизу?",
                                "покажи вечерние планы",
                                "что связано с набережной?",
                                "когда мне идти на встречу?"
                        )
                ),
                family(
                        "kitchen_shop",
                        "Купить фильтр, лампочки и полотенца для кухни",
                        "Купить фильтр, лампочки и полотенца для кухни",
                        "Нужно купить фильтр, лампочки и полотенца для кухни.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("кухня", "покупка", "фильтр", "лампочки"),
                        List.of(
                                "что купить для кухни?",
                                "какие покупки для дома?",
                                "что мне надо взять на кухню?",
                                "покажи покупки для кухни",
                                "что я собирался купить для дома?",
                                "какие вещи нужны для кухни?",
                                "что по кухонным покупкам?",
                                "какую бытовую покупку я сохранял?",
                                "что у меня в списке для кухни?",
                                "что нужно купить в квартиру?"
                        )
                ),
                family(
                        "family_calendar",
                        "Идея: семейный календарь для всех домашних дел",
                        "Идея: семейный календарь для всех домашних дел",
                        "Идея семейного календаря для общих домашних дел и напоминаний.",
                        InboxItemType.IDEA,
                        Set.of("идея", "календарь", "семья", "дом"),
                        List.of(
                                "какие семейные идеи я сохранял?",
                                "что я придумал для семьи?",
                                "покажи календарные идеи",
                                "что было про домашние дела?",
                                "какую идею для семьи я хотел?",
                                "что связано с семейным календарем?",
                                "какие планы для дома я записывал?",
                                "что я думал про общий календарь?",
                                "покажи семейные заметки",
                                "что я планировал для семьи?"
                        )
                )
        );
    }

    private List<ExtendedScenario> extendedScenariosList() {
        OffsetDateTime now = referenceNow == null ? OffsetDateTime.now() : referenceNow;
        return List.of(
                extended(
                        "english_watch",
                        "I want to watch Severance",
                        "I want to watch Severance",
                        "I want to watch Severance tonight.",
                        InboxItemType.MOVIE,
                        Set.of("movie", "watch", "severance"),
                        now.minusDays(2),
                        "what do I want to watch?"
                ),
                extended(
                        "english_read",
                        "I want to read Atomic Habits",
                        "I want to read Atomic Habits",
                        "I want to read Atomic Habits this week.",
                        InboxItemType.BOOK,
                        Set.of("book", "read", "habits"),
                        now.minusDays(3),
                        "what should I read?"
                ),
                extended(
                        "english_kafka",
                        "I saved notes about Kafka streams",
                        "I saved notes about Kafka streams",
                        "Notes about Kafka streams, consumers, and partitions.",
                        InboxItemType.NOTE,
                        Set.of("kafka", "streams", "notes"),
                        now.minusDays(1),
                        "what did I save about Kafka?"
                ),
                extended(
                        "english_purchase",
                        "Need to buy a chair for my apartment desk",
                        "Need to buy a chair for my apartment desk",
                        "Need to buy a chair for my apartment desk after payday.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("buy", "chair", "apartment"),
                        now.minusDays(4),
                        "what do I need to buy for the apartment?"
                ),
                extended(
                        "english_delay",
                        "I'm postponing tax paperwork",
                        "I'm postponing tax paperwork",
                        "I'm postponing tax paperwork until later.",
                        InboxItemType.TASK,
                        Set.of("tax", "paperwork", "delay"),
                        now.minusDays(2),
                        "what am I postponing?"
                ),
                extended(
                        "english_promise_buy",
                        "Buy a router after payday",
                        "Buy a router after payday",
                        "Buy a router after payday for home internet.",
                        InboxItemType.TASK,
                        Set.of("buy", "router", "payday"),
                        now.minusDays(5),
                        "what did I promise to buy after payday?"
                ),
                extended(
                        "english_health",
                        "Buy vitamin D after doctor visit",
                        "Buy vitamin D after doctor visit",
                        "Buy vitamin D after doctor visit and ask about dosage.",
                        InboxItemType.HEALTH,
                        Set.of("health", "vitamin d", "doctor"),
                        now.minusDays(1),
                        "what should I do about my health?"
                ),
                extended(
                        "english_idea",
                        "Idea for a Telegram memory app",
                        "Idea for a Telegram memory app",
                        "Idea for a Telegram memory app with AI search.",
                        InboxItemType.IDEA,
                        Set.of("idea", "telegram", "search"),
                        now.minusDays(3),
                        "what idea did I write down about the app?"
                ),
                extended(
                        "english_food",
                        "Try dumplings tonight",
                        "Try dumplings tonight",
                        "Try dumplings tonight or another quick dinner.",
                        InboxItemType.IDEA,
                        Set.of("food", "dumplings", "dinner"),
                        now.minusDays(1),
                        "what did I want to cook tonight?"
                ),
                extended(
                        "english_story",
                        "Andrey told me a story about the night train",
                        "Andrey told me a story about the night train",
                        "Andrey told me a story about the night train and a delay.",
                        InboxItemType.NOTE,
                        Set.of("andrey", "story", "train"),
                        now.minusDays(2),
                        "who told me the story about Andrey?"
                ),
                extended(
                        "typo_watch",
                        "Хочу посмотреть сериал Разделение",
                        "Хочу посмотреть сериал Разделение",
                        "Хочу посмотреть сериал Разделение вечером.",
                        InboxItemType.MOVIE,
                        Set.of("сериал", "разделение", "просмотр"),
                        now.minusDays(1),
                        "что я хател посмотреть?"
                ),
                extended(
                        "typo_project",
                        "Идея проекта: личная память в Telegram с AI поиском",
                        "Идея проекта: личная память в Telegram с AI поиском",
                        "Идея проекта про личную память в Telegram с AI поиском.",
                        InboxItemType.IDEA,
                        Set.of("проект", "telegram", "ai", "поиск"),
                        now.minusDays(1),
                        "какие идей я записывал про пет проект?"
                ),
                extended(
                        "typo_recipe",
                        "Рецепт пельменей",
                        "Рецепт пельменей",
                        "Рецепт пельменей с фаршем, тестом и луком.",
                        InboxItemType.NOTE,
                        Set.of("рецепт", "пельмени", "фарш"),
                        now.minusDays(1),
                        "покажи рицепты с фаршем"
                ),
                extended(
                        "typo_work",
                        "Нужен удобный новый стул для рабочего места дома",
                        "Нужен удобный новый стул для рабочего места дома",
                        "Нужен удобный новый стул для рабочего места дома.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("покупка", "стул", "работа"),
                        now.minusDays(2),
                        "что мне купить для раб. места"
                ),
                extended(
                        "typo_read",
                        "Хочу прочитать книгу Заводной апельсин",
                        "Хочу прочитать книгу Заводной апельсин",
                        "Хочу прочитать книгу Заводной апельсин и обсудить её позже.",
                        InboxItemType.BOOK,
                        Set.of("книга", "чтение", "литература"),
                        now.minusDays(3),
                        "что я хотел почетать?"
                ),
                extended(
                        "typo_night",
                        "Вечером встреча с Лизой у набережной",
                        "Вечером встреча с Лизой у набережной",
                        "Вечером встреча с Лизой у набережной, возможно в кафе рядом.",
                        InboxItemType.REMINDER,
                        Set.of("встреча", "лиза", "вечер"),
                        now.minusDays(1),
                        "что у меня на вечер?"
                ),
                extended(
                        "today_health",
                        "Купить витамин D после врача",
                        "Купить витамин D после врача",
                        "Купить витамин D после врача сегодня вечером.",
                        InboxItemType.HEALTH,
                        Set.of("здоровье", "витамин d", "врач"),
                        now,
                        "что я сегодня сохранял про здоровье?"
                ),
                extended(
                        "today_project",
                        "Разобраться с pgvector для семантического поиска в PostgreSQL",
                        "Разобраться с pgvector для семантического поиска в PostgreSQL",
                        "Разобраться с pgvector для семантического поиска в PostgreSQL сегодня.",
                        InboxItemType.TASK,
                        Set.of("postgresql", "pgvector", "поиск"),
                        now,
                        "что я сегодня записал про pgvector?"
                ),
                extended(
                        "today_watch",
                        "Посмотреть сериал Разделение",
                        "Посмотреть сериал Разделение",
                        "Посмотреть сериал Разделение сегодня вечером.",
                        InboxItemType.MOVIE,
                        Set.of("сериал", "разделение", "просмотр"),
                        now,
                        "что я хотел посмотреть сегодня?"
                ),
                extended(
                        "today_purchase",
                        "Купить фильтр для воды",
                        "Купить фильтр для воды",
                        "Купить фильтр для воды сегодня после работы.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("покупка", "фильтр", "вода"),
                        now,
                        "что я хотел купить сегодня?"
                ),
                extended(
                        "today_reminder",
                        "Напоминание позвонить врачу",
                        "Напоминание позвонить врачу",
                        "Напоминание позвонить врачу сегодня вечером.",
                        InboxItemType.REMINDER,
                        Set.of("напоминание", "врач", "сегодня"),
                        now,
                        "что у меня сегодня?"
                ),
                extended(
                        "yesterday_kafka",
                        "Сохранить заметку про Kafka consumers",
                        "Сохранить заметку про Kafka consumers",
                        "Сохранить заметку про Kafka consumers вчера вечером.",
                        InboxItemType.NOTE,
                        Set.of("kafka", "consumers", "заметка"),
                        now.minusDays(1),
                        "что я сохранил вчера?"
                ),
                extended(
                        "yesterday_food",
                        "Рецепт картохи в духовке",
                        "Рецепт картохи в духовке",
                        "Рецепт картохи в духовке с чесноком и травами.",
                        InboxItemType.NOTE,
                        Set.of("рецепт", "картоха", "еда"),
                        now.minusDays(1),
                        "что я вчера сохранял про картоху?"
                ),
                extended(
                        "yesterday_movie",
                        "Хочу посмотреть фильм Мгла",
                        "Хочу посмотреть фильм Мгла",
                        "Хочу посмотреть фильм Мгла вечером вчера.",
                        InboxItemType.MOVIE,
                        Set.of("фильм", "мгла", "просмотр"),
                        now.minusDays(1),
                        "что я хотел посмотреть вчера?"
                ),
                extended(
                        "yesterday_buy",
                        "Купить узкую светлую обувницу в прихожую",
                        "Купить узкую светлую обувницу в прихожую",
                        "Купить узкую светлую обувницу в прихожую вчера.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("покупка", "обувница", "прихожая"),
                        now.minusDays(1),
                        "что я хотел купить вчера?"
                ),
                extended(
                        "yesterday_lunch",
                        "Идея ужина: паста с курицей и грибами в сливочном соусе",
                        "Идея ужина: паста с курицей и грибами в сливочном соусе",
                        "Идея ужина с курицей и грибами вчера вечером.",
                        InboxItemType.IDEA,
                        Set.of("ужин", "паста", "курица"),
                        now.minusDays(1),
                        "что я вчера сохранял про ужин?"
                ),
                extended(
                        "yesterday_meeting",
                        "Встреча с Андрюхой и история про поезд",
                        "Встреча с Андрюхой и история про поезд",
                        "Встреча с Андрюхой и история про поезд вчера днем.",
                        InboxItemType.NOTE,
                        Set.of("андрюха", "история", "поезд"),
                        now.minusDays(1),
                        "что я вчера сохранял про встречу?"
                ),
                extended(
                        "yesterday_idea",
                        "Идея для приложения заметок с поиском",
                        "Идея для приложения заметок с поиском",
                        "Идея приложения для заметок с хорошим поиском и AI вчера.",
                        InboxItemType.IDEA,
                        Set.of("идея", "приложение", "поиск"),
                        now.minusDays(1),
                        "что я вчера откладывал про проект?"
                ),
                extended(
                        "yesterday_health",
                        "Купить лампочки E27 теплый свет",
                        "Купить лампочки E27 теплый свет",
                        "Купить лампочки E27 теплый свет вчера вечером.",
                        InboxItemType.PURCHASE_RESEARCH,
                        Set.of("лампочки", "покупка", "освещение"),
                        now.minusDays(1),
                        "что я вчера сохранял про лампочки?"
                ),
                extended(
                        "yesterday_tasks",
                        "Проверить расходы на подписки",
                        "Проверить расходы на подписки",
                        "Проверить расходы на подписки вчера.",
                        InboxItemType.TASK,
                        Set.of("финансы", "подписки", "расходы"),
                        now.minusDays(1),
                        "что мне надо было проверить вчера?"
                )
        );
    }

    private void seed(
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags
    ) {
        seed(rawText, title, summary, type, tags, null);
    }

    private void seed(
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            OffsetDateTime createdAt
    ) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        item.setTitle(title);
        item.setSummary(summary);
        item.setType(type);
        item.setStatus(InboxItemStatus.PROCESSED);
        item.setPriority(InboxItemPriority.MEDIUM);
        item.setTags(tags);
        MemoryUnit unit = new MemoryUnit(item, toMemoryUnitType(type), title);
        unit.setSummary(summary);
        unit.setSourceQuote(rawText);
        unit.setTags(tags);
        unit.setActionable(type == InboxItemType.TASK || type == InboxItemType.REMINDER);
        unit.setConfidence(1.0d);
        item.addMemoryUnit(unit);
        InboxItem saved = inboxItemRepository.saveAndFlush(item);
        if (createdAt != null) {
            saved.setCreatedAt(createdAt);
            inboxItemRepository.saveAndFlush(saved);
        }
    }

    private static MemoryUnitType toMemoryUnitType(InboxItemType type) {
        try {
            return MemoryUnitType.valueOf(type.name());
        } catch (IllegalArgumentException exception) {
            return MemoryUnitType.NOTE;
        }
    }

    private record ScenarioFamily(
            String label,
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            List<String> queries
    ) {
    }

    private record ExtendedScenario(
            String label,
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            OffsetDateTime createdAt,
            String query
    ) {
    }

    private static ScenarioFamily family(
            String label,
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            List<String> queries
    ) {
        return new ScenarioFamily(label, rawText, title, summary, type, tags, queries);
    }

    private ExtendedScenario extended(
            String label,
            String rawText,
            String title,
            String summary,
            InboxItemType type,
            Set<String> tags,
            OffsetDateTime createdAt,
            String query
    ) {
        return new ExtendedScenario(label, rawText, title, summary, type, tags, createdAt, query);
    }
}
