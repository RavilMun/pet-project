package ru.ravil.petproject.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ravil.petproject.service.DueReminder;
import ru.ravil.petproject.service.MemoryReminderService;

/**
 * Delivers due TASK/REMINDER memory units to the Telegram chat that captured them.
 * Only active when the bot is enabled; a reminder is marked delivered only after a successful
 * send, so transient send failures are retried on the next sweep.
 */
@Component
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TelegramReminderScheduler.class);
    private static final int BATCH_LIMIT = 50;

    private final MemoryReminderService memoryReminderService;
    private final TelegramApiClient telegramApiClient;

    public TelegramReminderScheduler(
            MemoryReminderService memoryReminderService,
            TelegramApiClient telegramApiClient
    ) {
        this.memoryReminderService = memoryReminderService;
        this.telegramApiClient = telegramApiClient;
    }

    @Scheduled(
            initialDelayString = "${telegram.bot.reminder.initial-delay-ms:30000}",
            fixedDelayString = "${telegram.bot.reminder.interval-ms:60000}"
    )
    void deliverDueReminders() {
        for (DueReminder reminder : memoryReminderService.dueReminders(BATCH_LIMIT)) {
            try {
                telegramApiClient.sendMessage(reminder.chatId(), format(reminder));
                memoryReminderService.markReminded(reminder.unitId());
            } catch (RuntimeException exception) {
                log.warn("Failed to deliver reminder {}: {}", reminder.unitId(), exception.getMessage());
            }
        }
    }

    private String format(DueReminder reminder) {
        return "⏰ Напоминание: " + reminder.title();
    }
}
