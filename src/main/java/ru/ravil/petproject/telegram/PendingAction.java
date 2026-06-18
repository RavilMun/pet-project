package ru.ravil.petproject.telegram;

import java.util.List;

/**
 * A per-chat action awaiting a yes/no confirmation (single candidate) or a numeric pick
 * (multiple candidates). {@code param} carries the snooze duration / new edit text.
 */
public record PendingAction(
        TelegramActionType type,
        List<PendingTarget> candidates,
        String param
) {
}
