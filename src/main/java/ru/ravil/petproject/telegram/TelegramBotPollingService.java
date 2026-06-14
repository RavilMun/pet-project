package ru.ravil.petproject.telegram;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.config.TelegramBotProperties;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.dto.InboxItemResponse;
import ru.ravil.petproject.service.AiProcessingUnavailableException;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.InboxItemService;

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
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicLong nextOffset = new AtomicLong(0);

    public TelegramBotPollingService(
            TelegramApiClient telegramApiClient,
            TelegramBotProperties properties,
            InboxItemService inboxItemService,
            InboxItemSearchService inboxItemSearchService,
            CommandTelegramIntentDetector commandTelegramIntentDetector,
            RuleBasedTelegramIntentDetector ruleBasedTelegramIntentDetector,
            AiTelegramIntentDetector aiTelegramIntentDetector,
            TelegramSearchResponseFormatter searchResponseFormatter,
            InboxItemEmbeddingBackfillService embeddingBackfillService
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

        TelegramIntent intent = commandTelegramIntentDetector.detect(normalizedText);
        if (intent.isUnknown()) {
            intent = ruleBasedTelegramIntentDetector.detect(normalizedText);
        }
        if (intent.isUnknown()) {
            intent = aiTelegramIntentDetector.detect(normalizedText);
        }
        if (!intent.isUnknown() && handleIntent(chatId, intent)) {
            return;
        }

        try {
            InboxItemResponse savedItem = inboxItemService.create(new CreateInboxItemRequest(
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
        } catch (AiProcessingUnavailableException exception) {
            telegramApiClient.sendMessage(chatId, "Не сохранил: AI-обработка сейчас недоступна. Попробуй позже.");
        }
    }

    private boolean handleIntent(long chatId, TelegramIntent intent) {
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
                List<InboxItemResponse> items = inboxItemSearchService.search(
                        intent.query(),
                        intent.itemTypes(),
                        intent.tags(),
                        intent.period(),
                        10
                );
                telegramApiClient.sendMessage(chatId, searchResponseFormatter.format(intent.query(), items));
                yield true;
            }
            case UNKNOWN, CAPTURE -> false;
        };
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
        StringBuilder builder = new StringBuilder("Сохранил: ")
                .append(displayText(item))
                .append("\nТип: ")
                .append(item.type());

        if (!item.tags().isEmpty()) {
            builder.append("\nТеги: ").append(String.join(", ", item.tags()));
        }

        return builder.toString();
    }
}
