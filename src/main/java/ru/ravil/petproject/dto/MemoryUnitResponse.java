package ru.ravil.petproject.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import ru.ravil.petproject.domain.MemoryUnitType;

public record MemoryUnitResponse(
        UUID id,
        UUID inboxItemId,
        String sourceRawText,
        String title,
        String summary,
        MemoryUnitType type,
        Set<String> tags,
        Set<MemorySlotResponse> slots,
        boolean actionable,
        double confidence,
        String sourceQuote,
        OffsetDateTime occurredAt,
        OffsetDateTime dueAt,
        OffsetDateTime sourceCreatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
