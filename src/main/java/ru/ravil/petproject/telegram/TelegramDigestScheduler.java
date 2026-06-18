package ru.ravil.petproject.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ravil.petproject.config.TelegramBotProperties;
import ru.ravil.petproject.service.MemoryDigestService;

/**
 * Proactive morning digest (Feature 8.2): on a cron schedule, sends the allowed chat a summary of open
 * tasks and recent captures. Gated by {@code telegram.bot.enabled} (off in tests). Requires
 * {@code telegram.bot.allowed-chat-id} to know where to send; skips if unset.
 */
@Component
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(TelegramDigestScheduler.class);

    private final TelegramBotProperties properties;
    private final MemoryDigestService digestService;
    private final TelegramApiClient telegramApiClient;

    public TelegramDigestScheduler(
            TelegramBotProperties properties,
            MemoryDigestService digestService,
            TelegramApiClient telegramApiClient
    ) {
        this.properties = properties;
        this.digestService = digestService;
        this.telegramApiClient = telegramApiClient;
    }

    @Scheduled(cron = "${telegram.bot.digest.cron}")
    void sendDigest() {
        Long chatId = properties.allowedChatId();
        if (chatId == null) {
            log.debug("Digest skipped: telegram.bot.allowed-chat-id is not set");
            return;
        }
        try {
            digestService.buildDigest(chatId)
                    .ifPresent(text -> telegramApiClient.sendMessage(chatId, text));
        } catch (RuntimeException exception) {
            log.warn("Digest send failed for chat {}: {}", chatId, exception.getMessage());
        }
    }
}
