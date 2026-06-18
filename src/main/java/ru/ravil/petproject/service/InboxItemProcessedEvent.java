package ru.ravil.petproject.service;

import java.util.UUID;

/**
 * Published after an inbox item is successfully enriched (classified, units extracted and embedded).
 * Lets decoupled listeners react — e.g. the Telegram "related memories" notifier (Feature: connections
 * on capture). {@code telegramChatId} is null for non-Telegram captures.
 */
public record InboxItemProcessedEvent(
        UUID itemId,
        Long telegramChatId
) {
}
