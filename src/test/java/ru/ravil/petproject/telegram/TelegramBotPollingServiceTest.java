package ru.ravil.petproject.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import ru.ravil.petproject.config.TelegramBotProperties;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.service.AiProcessingUnavailableException;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchPeriod;

class TelegramBotPollingServiceTest {

    private TelegramApiClient telegramApiClient;
    private InboxItemService inboxItemService;
    private InboxItemSearchService inboxItemSearchService;
    private InboxItemEmbeddingBackfillService embeddingBackfillService;
    private AiTelegramIntentDetector aiTelegramIntentDetector;

    @BeforeEach
    void setUp() {
        telegramApiClient = Mockito.mock(TelegramApiClient.class);
        inboxItemService = Mockito.mock(InboxItemService.class);
        inboxItemSearchService = Mockito.mock(InboxItemSearchService.class);
        embeddingBackfillService = Mockito.mock(InboxItemEmbeddingBackfillService.class);
        aiTelegramIntentDetector = Mockito.mock(AiTelegramIntentDetector.class);
        when(aiTelegramIntentDetector.detect(anyString())).thenReturn(TelegramIntent.unknown());
    }

    @Test
    void pollSavesTextMessageToInbox() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = update(100, 42, "remember docs");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));
        when(inboxItemService.create(any(CreateInboxItemRequest.class))).thenReturn(response());

        service.poll();

        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).create(captor.capture());
        CreateInboxItemRequest request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.rawText()).isEqualTo("remember docs");
        org.assertj.core.api.Assertions.assertThat(request.type()).isNull();
        org.assertj.core.api.Assertions.assertThat(request.source()).isEqualTo(InboxItemSource.TELEGRAM);
        org.assertj.core.api.Assertions.assertThat(request.telegramChatId()).isEqualTo(42);
        org.assertj.core.api.Assertions.assertThat(request.telegramMessageId()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(request.tags()).isEmpty();
        verify(telegramApiClient).sendMessage(42, "Сохранил: remember docs\nТип: NOTE");
    }

    @Test
    void pollDoesNotSaveTextMessageWhenAiProcessingIsUnavailable() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = update(100, 42, "remember docs");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));
        when(inboxItemService.create(any(CreateInboxItemRequest.class)))
                .thenThrow(new AiProcessingUnavailableException());

        service.poll();

        verify(inboxItemService).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Не сохранил: AI-обработка сейчас недоступна. Попробуй позже.");
    }

    @Test
    void pollRespondsToStartCommandWithoutSavingItem() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/start")));

        service.poll();

        verifyNoInteractions(inboxItemService);
        verify(telegramApiClient).sendMessage(42, "Можешь просто писать заметки, ссылки и идеи.\nПримеры:\nнайди кресло\nпокажи последние\nчто я добавлял сегодня");
    }

    @Test
    void pollReturnsChatInfoForWhoamiCommand() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/whoami")));

        service.poll();

        verifyNoInteractions(inboxItemService);
        verify(telegramApiClient).sendMessage(42, "chatId: 42\ntype: private\nusername: ravil\nfirstName: Ravil");
    }

    @Test
    void pollReturnsChatInfoForWhoamiEvenWhenChatIsNotAllowed() {
        TelegramBotPollingService service = service(7L);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/whoami")));

        service.poll();

        verifyNoInteractions(inboxItemService);
        verify(telegramApiClient).sendMessage(42, "chatId: 42\ntype: private\nusername: ravil\nfirstName: Ravil");
    }

    @Test
    void pollReturnsRecentItems() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/recent")));
        when(inboxItemService.listRecent(5)).thenReturn(List.of(response("first"), response("second")));

        service.poll();

        verify(inboxItemService).listRecent(5);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Последние записи:\n1. first [NOTE]\n2. second [NOTE]");
    }

    @Test
    void pollReturnsTodayItems() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/today")));
        when(inboxItemService.listToday(10)).thenReturn(List.of(response("today note")));

        service.poll();

        verify(inboxItemService).listToday(10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Сегодня:\n1. today note [NOTE]");
    }

    @Test
    void pollReturnsEmptyRecentMessage() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/recent")));
        when(inboxItemService.listRecent(5)).thenReturn(List.of());

        service.poll();

        verify(telegramApiClient).sendMessage(42, "Последние записи: пусто.");
    }

    @Test
    void pollReturnsSearchResults() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/search pgvector")));
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(response("learn pgvector")));

        service.poll();

        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. learn pgvector\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollReturnsSearchResultsForNaturalLanguageSearch() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди pgvector")));
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(response("learn pgvector")));

        service.poll();

        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. learn pgvector\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollReturnsSearchResultsForGenericQuestionSearch() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "Что хотел посмотреть?")));
        when(inboxItemSearchService.search("хотел посмотреть", Set.of(InboxItemType.MOVIE), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(response("watch mist")));

        service.poll();

        verify(inboxItemSearchService).search("хотел посмотреть", Set.of(InboxItemType.MOVIE), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. watch mist\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollUsesAiFallbackForUnknownQuestion() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "напомни ту штуку для кухни")));
        when(aiTelegramIntentDetector.detect("напомни ту штуку для кухни"))
                .thenReturn(TelegramIntent.search("кухня"));
        when(inboxItemSearchService.search("кухня", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(response("выбрать штуку для кухни")));

        service.poll();

        verify(aiTelegramIntentDetector).detect("напомни ту штуку для кухни");
        verify(inboxItemSearchService).search("кухня", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. выбрать штуку для кухни\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollSavesMessageWhenAiFallbackReturnsCapture() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "напомни оплатить интернет завтра")));
        when(aiTelegramIntentDetector.detect("напомни оплатить интернет завтра"))
                .thenReturn(TelegramIntent.capture());
        when(inboxItemService.create(any(CreateInboxItemRequest.class))).thenReturn(response("напомни оплатить интернет завтра"));

        service.poll();

        verify(aiTelegramIntentDetector).detect("напомни оплатить интернет завтра");
        verify(inboxItemService).create(any(CreateInboxItemRequest.class));
        verify(inboxItemSearchService, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(telegramApiClient).sendMessage(42, "Сохранил: напомни оплатить интернет завтра\nТип: NOTE");
    }

    @Test
    void pollReturnsHelpfulMessageWhenSearchHasNoResults() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди кресло")));
        when(inboxItemSearchService.search("кресло", Set.of(), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of());

        service.poll();

        verify(inboxItemSearchService).search("кресло", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Ничего не нашёл по \"кресло\".\nПопробуй: найди кресло, покажи последние, что сохранил сегодня");
    }

    @Test
    void pollReturnsRecentItemsForNaturalLanguageRecentRequest() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "покажи последние")));
        when(inboxItemService.listRecent(5)).thenReturn(List.of(response("first")));

        service.poll();

        verify(inboxItemService).listRecent(5);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Последние записи:\n1. first [NOTE]");
    }

    @Test
    void pollSavesEmptySearchCommandAsNote() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/search")));
        when(inboxItemService.create(any(CreateInboxItemRequest.class))).thenReturn(response("/search"));

        service.poll();

        verify(inboxItemSearchService, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(inboxItemService).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Сохранил: /search\nТип: NOTE");
    }

    @Test
    void pollUpdatesLastTelegramItemType() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/type movie")));
        when(inboxItemService.updateLastTelegramItemType(42L, InboxItemType.MOVIE))
                .thenReturn(Optional.of(response("watch movie", InboxItemType.MOVIE)));

        service.poll();

        verify(inboxItemService).updateLastTelegramItemType(42L, InboxItemType.MOVIE);
        verify(telegramApiClient).sendMessage(42, "Обновил тип: MOVIE");
    }

    @Test
    void pollBackfillsEmbeddings() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/embeddings backfill")));
        when(embeddingBackfillService.backfillMissingEmbeddings(25)).thenReturn(7);

        service.poll();

        verify(embeddingBackfillService).backfillMissingEmbeddings(25);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Embeddings backfill: обновлено 7 записей.");
    }

    @Test
    void pollReturnsUsageForUnknownEmbeddingsCommand() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/embeddings nope")));

        service.poll();

        verify(embeddingBackfillService, never()).backfillMissingEmbeddings(org.mockito.ArgumentMatchers.any());
        verify(telegramApiClient).sendMessage(42, "Команда: /embeddings backfill");
    }

    @Test
    void pollAsksForTypeWhenTypeCommandIsEmpty() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/type")));

        service.poll();

        verify(inboxItemService, never()).updateLastTelegramItemType(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(telegramApiClient).sendMessage(42, "Укажи тип: /type MOVIE");
    }

    @Test
    void pollRejectsUnknownType() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/type unknown")));

        service.poll();

        verify(inboxItemService, never()).updateLastTelegramItemType(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(telegramApiClient).sendMessage(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.startsWith("Неизвестный тип: unknown"));
    }

    @Test
    void pollReportsWhenNoLastItemExistsForTypeUpdate() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/type MOVIE")));
        when(inboxItemService.updateLastTelegramItemType(42L, InboxItemType.MOVIE)).thenReturn(Optional.empty());

        service.poll();

        verify(telegramApiClient).sendMessage(42, "Не нашел последнюю запись для этого чата.");
    }

    @Test
    void pollIgnoresUnauthorizedChat() {
        TelegramBotPollingService service = service(7L);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "secret")));

        service.poll();

        verifyNoInteractions(inboxItemService);
        verify(telegramApiClient).sendMessage(42, "Этот бот пока приватный.");
    }

    private TelegramBotPollingService service(Long allowedChatId) {
        return new TelegramBotPollingService(
                telegramApiClient,
                new TelegramBotProperties(true, "token", "bot", allowedChatId),
                inboxItemService,
                inboxItemSearchService,
                new CommandTelegramIntentDetector(),
                new RuleBasedTelegramIntentDetector(new NaturalLanguageSearchQueryParser()),
                aiTelegramIntentDetector,
                new TelegramSearchResponseFormatter(Clock.fixed(
                        Instant.parse("2026-06-13T09:00:00Z"),
                        ZoneId.of("Europe/Moscow")
                )),
                embeddingBackfillService
        );
    }

    private static TelegramUpdate update(long updateId, long chatId, String text) {
        return new TelegramUpdate(updateId, new TelegramMessage(1L, new TelegramChat(chatId, "private", "Ravil", "ravil"), text));
    }

    private static InboxItemResponse response() {
        return response("remember docs");
    }

    private static InboxItemResponse response(String rawText) {
        return response(rawText, InboxItemType.NOTE);
    }

    private static InboxItemResponse response(String rawText, InboxItemType type) {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new InboxItemResponse(
                id,
                rawText,
                null,
                null,
                type,
                InboxItemStatus.NEW,
                InboxItemSource.TELEGRAM,
                InboxItemPriority.MEDIUM,
                false,
                42L,
                1L,
                Set.of(),
                Set.of(),
                null,
                OffsetDateTime.parse("2026-06-12T12:00:00Z"),
                OffsetDateTime.parse("2026-06-12T12:00:00Z")
        );
    }
}
