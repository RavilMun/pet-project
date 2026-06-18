package ru.ravil.petproject.service;

import java.util.UUID;

/**
 * Published after an inbox item's AI processing finishes (success or failure). Lets decoupled
 * listeners react — Telegram capture feedback reaction (👍/👎 on the user's message) and the
 * "related memories" notifier (only on success). {@code telegramChatId}/{@code telegramMessageId}
 * are null for non-Telegram captures.
 */
public record InboxItemProcessedEvent(
        UUID itemId,
        Long telegramChatId,
        Long telegramMessageId,
        boolean success
) {
}
