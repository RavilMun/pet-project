package ru.ravil.petproject.service;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OpenTask(
        UUID id,
        String title,
        OffsetDateTime dueAt
) {
}
