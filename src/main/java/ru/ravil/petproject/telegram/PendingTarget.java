package ru.ravil.petproject.telegram;

import java.util.UUID;

/** A candidate item (task or memory unit) for a pending action awaiting confirmation/disambiguation. */
public record PendingTarget(UUID id, String title) {
}
