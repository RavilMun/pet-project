package ru.ravil.petproject.telegram;

/**
 * A parsed natural-language action (Phase 9.2): {@code target} is the free-form description of the
 * item ("покупка стула"), {@code param} is action-specific (snooze duration like "2h", or the new
 * text for EDIT). {@link TelegramActionType#NONE} means "not an action".
 */
public record TelegramAction(
        TelegramActionType type,
        String target,
        String param
) {

    public static TelegramAction none() {
        return new TelegramAction(TelegramActionType.NONE, null, null);
    }

    public boolean isActionable() {
        return type != null && type != TelegramActionType.NONE;
    }
}
