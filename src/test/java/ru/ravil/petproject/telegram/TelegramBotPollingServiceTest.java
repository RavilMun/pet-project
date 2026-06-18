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
import ru.ravil.petproject.config.TelegramIntentMode;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.MemoryAnswer;
import ru.ravil.petproject.service.MemoryAnswerService;
import ru.ravil.petproject.service.MemoryDeduplicationService;
import ru.ravil.petproject.service.MemoryEditService;
import ru.ravil.petproject.service.MemoryTaskService;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.OpenTask;
import ru.ravil.petproject.service.SearchPeriod;

class TelegramBotPollingServiceTest {

    private TelegramApiClient telegramApiClient;
    private InboxItemService inboxItemService;
    private InboxItemSearchService inboxItemSearchService;
    private InboxItemEmbeddingBackfillService embeddingBackfillService;
    private MemoryAnswerService memoryAnswerService;
    private MemoryTaskService memoryTaskService;
    private MemoryEditService memoryEditService;
    private MemoryDeduplicationService deduplicationService;
    private TelegramImageIngestionService imageIngestionService;
    private TelegramVoiceIngestionService voiceIngestionService;
    private AiTelegramIntentDetector aiTelegramIntentDetector;

    @BeforeEach
    void setUp() {
        telegramApiClient = Mockito.mock(TelegramApiClient.class);
        inboxItemService = Mockito.mock(InboxItemService.class);
        inboxItemSearchService = Mockito.mock(InboxItemSearchService.class);
        embeddingBackfillService = Mockito.mock(InboxItemEmbeddingBackfillService.class);
        memoryAnswerService = Mockito.mock(MemoryAnswerService.class);
        memoryTaskService = Mockito.mock(MemoryTaskService.class);
        memoryEditService = Mockito.mock(MemoryEditService.class);
        deduplicationService = Mockito.mock(MemoryDeduplicationService.class);
        imageIngestionService = Mockito.mock(TelegramImageIngestionService.class);
        voiceIngestionService = Mockito.mock(TelegramVoiceIngestionService.class);
        aiTelegramIntentDetector = Mockito.mock(AiTelegramIntentDetector.class);
        when(aiTelegramIntentDetector.detect(anyString())).thenReturn(TelegramIntent.unknown());
        when(aiTelegramIntentDetector.detectAny(anyString())).thenReturn(TelegramIntent.unknown());
        when(memoryAnswerService.answer(anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void pollSavesTextMessageToInbox() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = update(100, 42, "remember docs");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response());

        service.poll();

        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).captureAsync(captor.capture());
        CreateInboxItemRequest request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.rawText()).isEqualTo("remember docs");
        org.assertj.core.api.Assertions.assertThat(request.type()).isNull();
        org.assertj.core.api.Assertions.assertThat(request.source()).isEqualTo(InboxItemSource.TELEGRAM);
        org.assertj.core.api.Assertions.assertThat(request.telegramChatId()).isEqualTo(42);
        org.assertj.core.api.Assertions.assertThat(request.telegramMessageId()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(request.tags()).isEmpty();
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: remember docs\nТип: NOTE");
    }

    @Test
    void pollDelegatesPhotoToImageIngestionAndAcks() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = updateWithPhoto(100, 42, "на чеке итого 1500", "file_42");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));

        service.poll();

        verify(imageIngestionService).ingest(42L, "file_42", "на чеке итого 1500", 1L);
        verify(telegramApiClient).sendMessage(42, "Сохранил картинку, разберу позже.");
        verify(inboxItemService, never()).captureAsync(any(CreateInboxItemRequest.class));
    }

    @Test
    void pollDelegatesVoiceToVoiceIngestionAndAcks() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = updateWithVoice(100, 42, "voice_42");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));

        service.poll();

        verify(voiceIngestionService).ingest(42L, "voice_42", 1L);
        verify(telegramApiClient).sendMessage(42, "Сохранил голосовое, разберу позже.");
        verify(inboxItemService, never()).captureAsync(any(CreateInboxItemRequest.class));
    }

    @Test
    void pollRejectsTooLongVoiceWithoutIngesting() {
        TelegramBotPollingService service = service(null);
        TelegramVoice longVoice = new TelegramVoice("voice_long", "vu", 900, "audio/ogg", 999_999);
        TelegramUpdate update = new TelegramUpdate(100L,
                new TelegramMessage(1L, new TelegramChat(42L, "private", "Ravil", "ravil"), null, null, null, longVoice));
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));

        service.poll();

        verifyNoInteractions(voiceIngestionService);
        verify(telegramApiClient).sendMessage(42, "Голосовое длиннее 10 мин — не сохранил. Запиши покороче.");
    }

    @Test
    void pollSavesStatementWithAboutWordInsteadOfSearching() {
        TelegramBotPollingService service = service(null);
        String text = "Вчера вечером читал статью про pgvector и понял, что embeddings лучше использовать вместе с full-text search";
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, text)));
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response(text));

        service.poll();

        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).captureAsync(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().rawText()).isEqualTo(text);
        verify(inboxItemSearchService, never()).search(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: " + text.substring(0, 77) + "...\nТип: NOTE");
    }

    @Test
    void pollSavesPossessiveFactInsteadOfSearching() {
        TelegramBotPollingService service = service(null);
        String text = "Мой терапевт Иванова.";
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, text)));
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response("Мой терапевт Иванова"));

        service.poll();

        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).captureAsync(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().rawText()).isEqualTo(text);
        verify(inboxItemSearchService, never()).search(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void pollAcknowledgesCaptureWhileAiProcessesInBackground() {
        TelegramBotPollingService service = service(null);
        TelegramUpdate update = update(100, 42, "remember docs");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update));
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response());

        service.poll();

        verify(inboxItemService).captureAsync(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: remember docs\nТип: NOTE");
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
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(memoryResponse("learn pgvector")));

        service.poll();

        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. learn pgvector\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollReturnsSearchResultsForNaturalLanguageSearch() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди pgvector")));
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(memoryResponse("learn pgvector")));

        service.poll();

        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. learn pgvector\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void commandOnlyModeSavesNaturalLanguageSearchAsNote() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.COMMAND_ONLY);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди pgvector")));
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response("найди pgvector"));

        service.poll();

        verify(inboxItemSearchService, never()).search(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(aiTelegramIntentDetector, never()).detect(anyString());
        verify(inboxItemService).captureAsync(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: найди pgvector\nТип: NOTE");
    }

    @Test
    void commandOnlyModeStillSupportsExplicitSearchCommand() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.COMMAND_ONLY);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/search pgvector")));
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memoryResponse("learn pgvector")));

        service.poll();

        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
    }

    @Test
    void hybridSafeModeUsesRulesBeforeAi() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.HYBRID_SAFE);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди pgvector")));
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memoryResponse("learn pgvector")));

        service.poll();

        verify(aiTelegramIntentDetector, never()).detect(anyString());
        verify(inboxItemSearchService).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
    }

    @Test
    void hybridSafeModeUsesAiBeforeRulesForNaturalQuestion() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.HYBRID_SAFE);
        String text = "Где я работал сегодня?";
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, text)));
        when(aiTelegramIntentDetector.shouldUseAiForIntent(text)).thenReturn(true);
        when(aiTelegramIntentDetector.detect(text))
                .thenReturn(TelegramIntent.search("я работал офис работа", Set.of(), Set.of(), SearchPeriod.TODAY));
        when(inboxItemSearchService.search("я работал офис работа", Set.of(), Set.of(), SearchPeriod.TODAY, 10))
                .thenReturn(List.of(memoryResponse("Встреча в офисе на Петроградской")));

        service.poll();

        verify(aiTelegramIntentDetector).detect(text);
        verify(inboxItemSearchService).search("я работал офис работа", Set.of(), Set.of(), SearchPeriod.TODAY, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
    }

    @Test
    void aiFirstModeUsesAiBeforeRules() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.AI_FIRST);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "найди pgvector")));
        when(aiTelegramIntentDetector.detectAny("найди pgvector")).thenReturn(TelegramIntent.search("semantic pgvector"));
        when(inboxItemSearchService.search("semantic pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memoryResponse("learn pgvector")));

        service.poll();

        verify(aiTelegramIntentDetector).detectAny("найди pgvector");
        verify(inboxItemSearchService).search("semantic pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemSearchService, never()).search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10);
    }

    @Test
    void aiFirstModeAsksAiForDeclarativeMessage() {
        TelegramBotPollingService service = service(null, TelegramIntentMode.AI_FIRST);
        String text = "Вчера вечером читал статью про pgvector и понял, что embeddings лучше использовать вместе с full-text search";
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, text)));
        when(aiTelegramIntentDetector.detectAny(text)).thenReturn(TelegramIntent.capture());
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response(text));

        service.poll();

        verify(aiTelegramIntentDetector).detectAny(text);
        verify(inboxItemService).captureAsync(any(CreateInboxItemRequest.class));
        verify(inboxItemSearchService, never()).search(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void pollReturnsSearchResultsForGenericQuestionSearch() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "Что хотел посмотреть?")));
        when(inboxItemSearchService.search("хотел посмотреть", Set.of(InboxItemType.MOVIE), Set.of(), SearchPeriod.ALL, 10)).thenReturn(List.of(memoryResponse("watch mist")));

        service.poll();

        verify(inboxItemSearchService).search("хотел посмотреть", Set.of(InboxItemType.MOVIE), Set.of(), SearchPeriod.ALL, 10);
        verify(inboxItemService, never()).create(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Нашёл 1 запись:\n\n1. watch mist\n   Тип: NOTE\n   Добавлено: вчера");
    }

    @Test
    void pollAddsMemoryAnswerBeforeSearchSourcesWhenAvailable() {
        TelegramBotPollingService service = service(null);
        MemoryUnitResponse source = memoryResponse("Купил USB-C кабель в магазине DNS");
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "где я купил кабель?")));
        when(inboxItemSearchService.search("купил кабель", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(source));
        when(memoryAnswerService.answer("где я купил кабель?", List.of(source)))
                .thenReturn(Optional.of(new MemoryAnswer("USB-C кабель куплен в магазине DNS.", List.of(source), 0.9)));

        service.poll();

        verify(inboxItemSearchService).search("купил кабель", Set.of(), Set.of(), SearchPeriod.ALL, 10);
        verify(telegramApiClient).sendMessage(42, """
                Ответ: USB-C кабель куплен в магазине DNS.

                Источники:
                1. Купил USB-C кабель в магазине DNS
                   Тип: NOTE
                   Добавлено: вчера""");
    }

    @Test
    void pollUsesAiFallbackForUnknownQuestion() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "напомни ту штуку для кухни")));
        when(aiTelegramIntentDetector.detect("напомни ту штуку для кухни"))
                .thenReturn(TelegramIntent.search("кухня"));
        when(inboxItemSearchService.search("кухня", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memoryResponse("выбрать штуку для кухни")));

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
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response("напомни оплатить интернет завтра"));

        service.poll();

        verify(aiTelegramIntentDetector).detect("напомни оплатить интернет завтра");
        verify(inboxItemService).captureAsync(any(CreateInboxItemRequest.class));
        verify(inboxItemSearchService, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: напомни оплатить интернет завтра\nТип: NOTE");
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
        when(inboxItemService.captureAsync(any(CreateInboxItemRequest.class))).thenReturn(response("/search"));

        service.poll();

        verify(inboxItemSearchService, never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(inboxItemService).captureAsync(any(CreateInboxItemRequest.class));
        verify(telegramApiClient).sendMessage(42, "Сохранил, разберу позже: /search\nТип: NOTE");
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

    @Test
    void pollListsOpenTasks() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/tasks")));
        when(memoryTaskService.listOpenTasks(42L, 20)).thenReturn(List.of(
                new OpenTask(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "Оплатить интернет",
                        OffsetDateTime.parse("2026-06-18T09:00:00Z")
                )
        ));

        service.poll();

        verify(telegramApiClient).sendMessage(42, "Открытые задачи:\n1. Оплатить интернет (до 2026-06-18)");
        verify(inboxItemService, never()).captureAsync(any(CreateInboxItemRequest.class));
    }

    @Test
    void pollForgetsMemoryFromLastSearchList() {
        TelegramBotPollingService service = service(null);
        MemoryUnitResponse memory = memoryResponse("learn pgvector");
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memory));
        when(memoryEditService.forget(memory.id())).thenReturn(true);
        when(telegramApiClient.getUpdates(0, 20))
                .thenReturn(List.of(update(100, 42, "найди pgvector"), update(101, 42, "/forget 1")));

        service.poll();

        verify(memoryEditService).forget(memory.id());
        verify(telegramApiClient).sendMessage(42, "Забыл: learn pgvector");
    }

    @Test
    void pollEditsMemoryFromLastSearchList() {
        TelegramBotPollingService service = service(null);
        MemoryUnitResponse memory = memoryResponse("learn pgvector");
        when(inboxItemSearchService.search("pgvector", Set.of(), Set.of(), SearchPeriod.ALL, 10))
                .thenReturn(List.of(memory));
        when(memoryEditService.edit(memory.id(), "учить pgvector внимательно"))
                .thenReturn(Optional.of(memoryResponse("учить pgvector внимательно")));
        when(telegramApiClient.getUpdates(0, 20))
                .thenReturn(List.of(update(100, 42, "найди pgvector"), update(101, 42, "/edit 1 учить pgvector внимательно")));

        service.poll();

        verify(memoryEditService).edit(memory.id(), "учить pgvector внимательно");
        verify(telegramApiClient).sendMessage(42, "Обновил: учить pgvector внимательно");
    }

    @Test
    void pollRejectsForgetWithoutPriorSearch() {
        TelegramBotPollingService service = service(null);
        when(telegramApiClient.getUpdates(0, 20)).thenReturn(List.of(update(100, 42, "/forget 1")));

        service.poll();

        verify(memoryEditService, never()).forget(any());
        verify(telegramApiClient).sendMessage(42, "Не нашёл запись под этим номером. Сначала найди, например: найди кресло");
    }

    private TelegramBotPollingService service(Long allowedChatId) {
        return service(allowedChatId, TelegramIntentMode.HYBRID_SAFE);
    }

    private TelegramBotPollingService service(Long allowedChatId, TelegramIntentMode intentMode) {
        return new TelegramBotPollingService(
                telegramApiClient,
                new TelegramBotProperties(true, "token", "bot", allowedChatId, intentMode),
                inboxItemService,
                inboxItemSearchService,
                new CommandTelegramIntentDetector(),
                new RuleBasedTelegramIntentDetector(new NaturalLanguageSearchQueryParser()),
                aiTelegramIntentDetector,
                new TelegramSearchResponseFormatter(Clock.fixed(
                        Instant.parse("2026-06-13T09:00:00Z"),
                        ZoneId.of("Europe/Moscow")
                )),
                embeddingBackfillService,
                memoryAnswerService,
                memoryTaskService,
                memoryEditService,
                deduplicationService,
                imageIngestionService,
                voiceIngestionService
        );
    }

    private static TelegramUpdate update(long updateId, long chatId, String text) {
        return new TelegramUpdate(updateId, new TelegramMessage(1L, new TelegramChat(chatId, "private", "Ravil", "ravil"), text, null, null, null));
    }

    private static TelegramUpdate updateWithPhoto(long updateId, long chatId, String caption, String fileId) {
        List<TelegramPhotoSize> photo = List.of(
                new TelegramPhotoSize("small", "u1", 90, 90, 1000),
                new TelegramPhotoSize(fileId, "u2", 800, 800, 50000)
        );
        return new TelegramUpdate(updateId,
                new TelegramMessage(1L, new TelegramChat(chatId, "private", "Ravil", "ravil"), null, photo, caption, null));
    }

    private static TelegramUpdate updateWithVoice(long updateId, long chatId, String fileId) {
        TelegramVoice voice = new TelegramVoice(fileId, "vu1", 7, "audio/ogg", 4096);
        return new TelegramUpdate(updateId,
                new TelegramMessage(1L, new TelegramChat(chatId, "private", "Ravil", "ravil"), null, null, null, voice));
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

    private static MemoryUnitResponse memoryResponse(String sourceRawText) {
        OffsetDateTime dateTime = OffsetDateTime.parse("2026-06-12T12:00:00Z");
        return new MemoryUnitResponse(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                sourceRawText,
                null,
                null,
                MemoryUnitType.NOTE,
                Set.of(),
                Set.of(),
                false,
                1.0,
                sourceRawText,
                null,
                null,
                null,
                dateTime,
                dateTime,
                dateTime
        );
    }
}
