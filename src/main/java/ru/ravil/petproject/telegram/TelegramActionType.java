package ru.ravil.petproject.telegram;

/**
 * Natural-language action intents (Phase 9.2) — what the user wants to *do* with an existing memory,
 * as opposed to capturing or searching. Resolved to a concrete task/memory by description, not index.
 */
public enum TelegramActionType {
    COMPLETE,
    SNOOZE,
    FORGET,
    EDIT,
    NONE
}
