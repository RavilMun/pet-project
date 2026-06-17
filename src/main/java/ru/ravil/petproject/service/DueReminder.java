package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DueReminder(
        UUID unitId,
        long chatId,
        String title,
        OffsetDateTime dueAt
) {
}
