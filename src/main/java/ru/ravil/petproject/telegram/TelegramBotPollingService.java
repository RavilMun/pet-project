package ru.ravil.petproject.telegram;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.config.TelegramBotProperties;
import ru.ravil.petproject.config.TelegramIntentMode;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.InboxItemService;
import ru.ravil.petproject.dto.DuplicatePairResponse;
import ru.ravil.petproject.service.MemoryAnswer;
import ru.ravil.petproject.service.MemoryAnswerService;
import ru.ravil.petproject.service.MemoryDeduplicationService;
import ru.ravil.petproject.service.MemoryEditService;
import ru.ravil.petproject.service.MemoryTaskService;
import ru.ravil.petproject.service.OpenTask;

@Service
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramBotPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPollingService.class);

    private final TelegramApiClient telegramApiClient;
    private final TelegramBotProperties properties;
    private final InboxItemService inboxItemService;
    private final InboxItemSearchService inboxItemSearchService;
    private final CommandTelegramIntentDetector commandTelegramIntentDetector;
    private final RuleBasedTelegramIntentDetector ruleBasedTelegramIntentDetector;
    private final AiTelegramIntentDetector aiTelegramIntentDetector;
    private final TelegramSearchResponseFormatter searchResponseFormatter;
    private final InboxItemEmbeddingBackfillService embeddingBackfillService;
    private final MemoryAnswerService memoryAnswerService;
    private final MemoryTaskService memoryTaskService;
    private final MemoryEditService memoryEditService;
    private final MemoryDeduplicationService deduplicationService;
    private final TelegramImageIngestionService imageIngestionService;
    private final TelegramVoiceIngestionService voiceIngestionService;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicLong nextOffset = new AtomicLong(0);
    private final Map<Long, List<OpenTask>> lastListedTasks = new ConcurrentHashMap<>();
    private final Map<Long, List<MemoryUnitResponse>> lastListedMemories = new ConcurrentHashMap<>();

    public TelegramBotPollingService(
            TelegramApiClient telegramApiClient,
            TelegramBotProperties properties,
            InboxItemService inboxItemService,
            InboxItemSearchService inboxItemSearchService,
            CommandTelegramIntentDetector commandTelegramIntentDetector,
            RuleBasedTelegramIntentDetector ruleBasedTelegramIntentDetector,
            AiTelegramIntentDetector aiTelegramIntentDetector,
            TelegramSearchResponseFormatter searchResponseFormatter,
            InboxItemEmbeddingBackfillService embeddingBackfillService,
            MemoryAnswerService memoryAnswerService,
            MemoryTaskService memoryTaskService,
            MemoryEditService memoryEditService,
            MemoryDeduplicationService deduplicationService,
            TelegramImageIngestionService imageIngestionService,
            TelegramVoiceIngestionService voiceIngestionService
    ) {
        this.telegramApiClient = telegramApiClient;
        this.properties = properties;
        this.inboxItemService = inboxItemService;
        this.inboxItemSearchService = inboxItemSearchService;
        this.commandTelegramIntentDetector = commandTelegramIntentDetector;
        this.ruleBasedTelegramIntentDetector = ruleBasedTelegramIntentDetector;
        this.aiTelegramIntentDetector = aiTelegramIntentDetector;
        this.searchResponseFormatter = searchResponseFormatter;
        this.embeddingBackfillService = embeddingBackfillService;
        this.memoryAnswerService = memoryAnswerService;
        this.memoryTaskService = memoryTaskService;
        this.memoryEditService = memoryEditService;
        this.deduplicationService = deduplicationService;
        this.imageIngestionService = imageIngestionService;
        this.voiceIngestionService = voiceIngestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logStartup() {
        log.info("Telegram bot polling is enabled for bot {}", properties.username());
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            for (TelegramUpdate update : telegramApiClient.getUpdates(nextOffset.get(), 20)) {
                handleUpdate(update);
                if (update.updateId() != null) {
                    nextOffset.set(update.updateId() + 1);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Telegram polling failed: {}", exception.getMessage());
        } finally {
            polling.set(false);
        }
    }

    private void handleUpdate(TelegramUpdate update) {
        TelegramMessage message = update.message();
        if (message == null || message.chat() == null) {
            return;
        }

        long chatId = message.chat().id();
        log.info("Received Telegram update {} from chat {}", update.updateId(), chatId);

        String text = message.text();
        String normalizedText = StringUtils.hasText(text) ? text.trim() : null;
        if ("/whoami".equals(normalizedText)) {
            telegramApiClient.sendMessage(chatId, formatChatInfo(message.chat()));
            return;
        }

        if (!isAllowed(chatId)) {
            log.warn("Ignoring Telegram message from unauthorized chat {}", chatId);
            telegramApiClient.sendMessage(chatId, "Этот бот пока приватный.");
            return;
        }

        TelegramPhotoSize photo = message.largestPhoto();
        if (photo != null) {
            imageIngestionService.ingest(chatId, photo.fileId(), message.caption(), message.messageId());
            telegramApiClient.sendMessage(chatId, "Сохранил картинку, разберу позже.");
            return;
        }

        TelegramVoice voice = message.voice();
        if (voice != null) {
            if (voice.duration() != null && voice.duration() > MAX_VOICE_SECONDS) {
                telegramApiClient.sendMessage(chatId,
                        "Голосовое длиннее " + (MAX_VOICE_SECONDS / 60) + " мин — не сохранил. Запиши покороче.");
                return;
            }
            voiceIngestionService.ingest(chatId, voice.fileId(), message.messageId());
            telegramApiClient.sendMessage(chatId, "Сохранил голосовое, разберу позже.");
            return;
        }

        if (!StringUtils.hasText(normalizedText)) {
            telegramApiClient.sendMessage(chatId, "Пока умею сохранять только текстовые сообщения.");
            return;
        }

        if (normalizedText.equals("/type") || normalizedText.startsWith("/type ")) {
            handleType(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/embeddings") || normalizedText.startsWith("/embeddings ")) {
            handleEmbeddings(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/tasks")) {
            handleTasks(chatId);
            return;
        }
        if (normalizedText.equals("/done") || normalizedText.startsWith("/done ")) {
            handleDone(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/snooze") || normalizedText.startsWith("/snooze ")) {
            handleSnooze(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/forget") || normalizedText.startsWith("/forget ")) {
            handleForget(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/edit") || normalizedText.startsWith("/edit ")) {
            handleEdit(chatId, normalizedText);
            return;
        }
        if (normalizedText.equals("/duplicates")) {
            handleDuplicates(chatId);
            return;
        }

        TelegramIntent intent = detectIntent(normalizedText);
        if (!intent.isUnknown() && handleIntent(chatId, normalizedText, intent)) {
            return;
        }

        InboxItemResponse savedItem = inboxItemService.captureAsync(new CreateInboxItemRequest(
                normalizedText,
                null,
                null,
                null,
                InboxItemSource.TELEGRAM,
                null,
                null,
                chatId,
                message.messageId(),
                Set.of()
        ));

        telegramApiClient.sendMessage(chatId, formatSavedItem(savedItem));
    }

    private TelegramIntent detectIntent(String text) {
        TelegramIntent commandIntent = commandTelegramIntentDetector.detect(text);
        if (!commandIntent.isUnknown()) {
            return commandIntent;
        }

        TelegramIntentMode intentMode = properties.intentMode();
        return switch (intentMode) {
            case COMMAND_ONLY -> TelegramIntent.unknown();
            case HYBRID_SAFE -> detectHybridSafeIntent(text);
            case AI_FIRST -> detectAiFirstIntent(text);
        };
    }

    private TelegramIntent detectHybridSafeIntent(String text) {
        if (aiTelegramIntentDetector.shouldUseAiForIntent(text)) {
            TelegramIntent aiIntent = aiTelegramIntentDetector.detect(text);
            if (!aiIntent.isUnknown()) {
                return aiIntent;
            }
        }

        TelegramIntent intent = ruleBasedTelegramIntentDetector.detect(text);
        if (intent.isUnknown()) {
            intent = aiTelegramIntentDetector.detect(text);
        }
        return intent;
    }

    private TelegramIntent detectAiFirstIntent(String text) {
        TelegramIntent intent = aiTelegramIntentDetector.detectAny(text);
        if (intent.isUnknown()) {
            intent = ruleBasedTelegramIntentDetector.detect(text);
        }
        return intent;
    }

    private boolean handleIntent(long chatId, String originalText, TelegramIntent intent) {
        return switch (intent.type()) {
            case HELP -> {
                telegramApiClient.sendMessage(chatId, "Можешь просто писать заметки, ссылки и идеи.\nПримеры:\nнайди кресло\nпокажи последние\nчто я добавлял сегодня");
                yield true;
            }
            case RECENT -> {
                telegramApiClient.sendMessage(chatId, formatItems("Последние записи", inboxItemService.listRecent(5)));
                yield true;
            }
            case TODAY -> {
                telegramApiClient.sendMessage(chatId, formatItems("Сегодня", inboxItemService.listToday(10)));
                yield true;
            }
            case SEARCH -> {
                List<MemoryUnitResponse> items = inboxItemSearchService.search(
                        intent.query(),
                        intent.itemTypes(),
                        intent.tags(),
                        intent.period(),
                        10
                );
                Optional<MemoryAnswer> answer = memoryAnswerService.answer(originalText, items);
                telegramApiClient.sendMessage(
                        chatId,
                        searchResponseFormatter.format(
                                StringUtils.hasText(intent.query()) ? intent.query() : originalText,
                                items,
                                answer
                        )
                );
                rememberListedMemories(chatId, items, answer);
                resendPhotos(chatId, items);
                yield true;
            }
            case UNKNOWN, CAPTURE -> false;
        };
    }

    private static final int MAX_RESENT_PHOTOS = 3;
    private static final int MAX_VOICE_SECONDS = 600;

    /** Re-sends the actual photo(s) behind image-backed search hits via {@code sendPhoto(file_id)}. */
    private void resendPhotos(long chatId, List<MemoryUnitResponse> items) {
        items.stream()
                .filter(item -> StringUtils.hasText(item.imageFileId()))
                .map(MemoryUnitResponse::imageFileId)
                .distinct()
                .limit(MAX_RESENT_PHOTOS)
                .forEach(fileId -> {
                    try {
                        telegramApiClient.sendPhoto(chatId, fileId, null);
                    } catch (RuntimeException exception) {
                        log.warn("Failed to resend photo {} to chat {}: {}", fileId, chatId, exception.getMessage());
                    }
                });
    }

    private boolean isAllowed(long chatId) {
        return properties.allowedChatId() == null || properties.allowedChatId() == chatId;
    }

    private String formatChatInfo(TelegramChat chat) {
        return "chatId: " + chat.id()
                + "\ntype: " + nullToDash(chat.type())
                + "\nusername: " + nullToDash(chat.username())
                + "\nfirstName: " + nullToDash(chat.firstName());
    }

    private String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private void handleType(long chatId, String command) {
        String rawType = command.substring("/type".length()).trim();
        if (!StringUtils.hasText(rawType)) {
            telegramApiClient.sendMessage(chatId, "Укажи тип: /type MOVIE");
            return;
        }

        InboxItemType type = parseType(rawType);
        if (type == null) {
            telegramApiClient.sendMessage(chatId, "Неизвестный тип: " + rawType + "\nДоступные типы: " + availableTypes());
            return;
        }

        inboxItemService.updateLastTelegramItemType(chatId, type)
                .ifPresentOrElse(
                        item -> telegramApiClient.sendMessage(chatId, "Обновил тип: " + item.type()),
                        () -> telegramApiClient.sendMessage(chatId, "Не нашел последнюю запись для этого чата.")
                );
    }

    private InboxItemType parseType(String rawType) {
        try {
            return InboxItemType.valueOf(rawType.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String availableTypes() {
        return String.join(", ", java.util.Arrays.stream(InboxItemType.values()).map(Enum::name).toList());
    }

    private void handleEmbeddings(long chatId, String command) {
        String argument = command.substring("/embeddings".length()).trim();
        if (!argument.isEmpty() && !argument.equals("backfill")) {
            telegramApiClient.sendMessage(chatId, "Команда: /embeddings backfill");
            return;
        }

        int updated = embeddingBackfillService.backfillMissingEmbeddings(25);
        telegramApiClient.sendMessage(chatId, "Embeddings backfill: обновлено " + updated + " записей.");
    }

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d+)\\s*(мин|min|m|ч|h|д|d)$", Pattern.CASE_INSENSITIVE);

    private void handleTasks(long chatId) {
        List<OpenTask> tasks = memoryTaskService.listOpenTasks(chatId, 20);
        lastListedTasks.put(chatId, tasks);
        telegramApiClient.sendMessage(chatId, formatTasks(tasks));
    }

    private void handleDone(long chatId, String command) {
        String argument = command.substring("/done".length()).trim();
        OpenTask task = resolveListedTask(chatId, argument);
        if (task == null) {
            telegramApiClient.sendMessage(chatId, "Не нашёл задачу под этим номером. Покажи список: /tasks");
            return;
        }
        boolean completed = memoryTaskService.completeTask(task.id());
        telegramApiClient.sendMessage(
                chatId,
                completed ? "Готово: " + taskTitle(task) : "Не удалось отметить задачу (возможно, уже выполнена)."
        );
    }

    private void handleSnooze(long chatId, String command) {
        String argument = command.substring("/snooze".length()).trim();
        String[] parts = argument.split("\\s+", 2);
        if (parts.length < 2) {
            telegramApiClient.sendMessage(chatId, "Формат: /snooze <номер> <интервал>, например /snooze 1 2h");
            return;
        }
        OpenTask task = resolveListedTask(chatId, parts[0]);
        if (task == null) {
            telegramApiClient.sendMessage(chatId, "Не нашёл задачу под этим номером. Покажи список: /tasks");
            return;
        }
        Duration delay = parseDuration(parts[1]);
        if (delay == null) {
            telegramApiClient.sendMessage(chatId, "Не понял интервал. Примеры: 30m, 2h, 1d");
            return;
        }
        boolean snoozed = memoryTaskService.snoozeTask(task.id(), delay);
        telegramApiClient.sendMessage(
                chatId,
                snoozed ? "Отложил: " + taskTitle(task) + " на " + parts[1] : "Не удалось отложить задачу."
        );
    }

    private void handleForget(long chatId, String command) {
        String argument = command.substring("/forget".length()).trim();
        MemoryUnitResponse memory = resolveListedMemory(chatId, argument);
        if (memory == null) {
            telegramApiClient.sendMessage(chatId, "Не нашёл запись под этим номером. Сначала найди, например: найди кресло");
            return;
        }
        boolean forgotten = memoryEditService.forget(memory.id());
        telegramApiClient.sendMessage(
                chatId,
                forgotten ? "Забыл: " + memoryTitle(memory) : "Не удалось забыть (возможно, уже забыто)."
        );
    }

    private void handleEdit(long chatId, String command) {
        String argument = command.substring("/edit".length()).trim();
        String[] parts = argument.split("\\s+", 2);
        if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
            telegramApiClient.sendMessage(chatId, "Формат: /edit <номер> <новый текст>");
            return;
        }
        MemoryUnitResponse memory = resolveListedMemory(chatId, parts[0]);
        if (memory == null) {
            telegramApiClient.sendMessage(chatId, "Не нашёл запись под этим номером. Сначала найди, например: найди кресло");
            return;
        }
        memoryEditService.edit(memory.id(), parts[1])
                .ifPresentOrElse(
                        updated -> telegramApiClient.sendMessage(chatId, "Обновил: " + memoryTitle(updated)),
                        () -> telegramApiClient.sendMessage(chatId, "Не удалось обновить запись.")
                );
    }

    private void handleDuplicates(long chatId) {
        List<DuplicatePairResponse> pairs = deduplicationService.findDuplicates(null, 20);
        if (pairs.isEmpty()) {
            telegramApiClient.sendMessage(chatId, "Похожих записей не нашёл.");
            return;
        }
        // Number the duplicate-side units and remember them so /forget <n> drops the redundant one.
        List<MemoryUnitResponse> duplicates = pairs.stream().map(DuplicatePairResponse::duplicate).toList();
        lastListedMemories.put(chatId, duplicates);

        StringBuilder builder = new StringBuilder("Похожие записи (лишнюю убери: /forget <номер>):\n");
        for (int i = 0; i < pairs.size(); i++) {
            DuplicatePairResponse pair = pairs.get(i);
            builder.append(i + 1).append(". ").append(memoryTitle(pair.duplicate()))
                    .append("\n   ≈ ").append(memoryTitle(pair.canonical()))
                    .append(" (").append(Math.round(pair.similarity() * 100)).append("%)\n");
        }
        telegramApiClient.sendMessage(chatId, builder.toString().trim());
    }

    private void rememberListedMemories(long chatId, List<MemoryUnitResponse> items, Optional<MemoryAnswer> answer) {
        // Mirror what the formatter actually numbers: cited sources when an answer is shown, else all items.
        List<MemoryUnitResponse> shown = answer.isPresent() && answer.get().hasText() && !answer.get().sources().isEmpty()
                ? answer.get().sources()
                : items;
        lastListedMemories.put(chatId, shown);
    }

    private MemoryUnitResponse resolveListedMemory(long chatId, String indexText) {
        List<MemoryUnitResponse> memories = lastListedMemories.get(chatId);
        if (memories == null || memories.isEmpty() || !StringUtils.hasText(indexText)) {
            return null;
        }
        Integer index = parsePositiveInt(indexText);
        if (index == null || index < 1 || index > memories.size()) {
            return null;
        }
        return memories.get(index - 1);
    }

    private String memoryTitle(MemoryUnitResponse memory) {
        String title = StringUtils.hasText(memory.title()) ? memory.title() : memory.sourceRawText();
        if (!StringUtils.hasText(title)) {
            return "(без названия)";
        }
        return title.length() <= 80 ? title : title.substring(0, 77) + "...";
    }

    private OpenTask resolveListedTask(long chatId, String indexText) {
        List<OpenTask> tasks = lastListedTasks.get(chatId);
        if (tasks == null || tasks.isEmpty() || !StringUtils.hasText(indexText)) {
            return null;
        }
        Integer index = parsePositiveInt(indexText);
        if (index == null || index < 1 || index > tasks.size()) {
            return null;
        }
        return tasks.get(index - 1);
    }

    private Integer parsePositiveInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Duration parseDuration(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "m", "min", "мин" -> Duration.ofMinutes(amount);
            case "h", "ч" -> Duration.ofHours(amount);
            case "d", "д" -> Duration.ofDays(amount);
            default -> null;
        };
    }

    private String formatTasks(List<OpenTask> tasks) {
        if (tasks.isEmpty()) {
            return "Открытых задач нет.";
        }
        StringBuilder builder = new StringBuilder("Открытые задачи:\n");
        for (int i = 0; i < tasks.size(); i++) {
            OpenTask task = tasks.get(i);
            builder.append(i + 1).append(". ").append(taskTitle(task));
            if (task.dueAt() != null) {
                builder.append(" (до ").append(task.dueAt().toLocalDate()).append(")");
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String taskTitle(OpenTask task) {
        String title = task.title();
        if (title == null) {
            return "(без названия)";
        }
        return title.length() <= 80 ? title : title.substring(0, 77) + "...";
    }

    private String formatItems(String title, List<InboxItemResponse> items) {
        if (items.isEmpty()) {
            return title + ": пусто.";
        }

        StringBuilder builder = new StringBuilder(title).append(":\n");
        for (int i = 0; i < items.size(); i++) {
            InboxItemResponse item = items.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(displayText(item))
                    .append(" [")
                    .append(item.type())
                    .append("]\n");
        }
        return builder.toString().trim();
    }

    private String displayText(InboxItemResponse item) {
        String text = StringUtils.hasText(item.title()) ? item.title() : item.rawText();
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private String formatSavedItem(InboxItemResponse item) {
        StringBuilder builder = new StringBuilder("Сохранил, разберу позже: ")
                .append(displayText(item))
                .append("\nТип: ")
                .append(item.type());

        if (!item.tags().isEmpty()) {
            builder.append("\nТеги: ").append(String.join(", ", item.tags()));
        }

        return builder.toString();
    }
}
