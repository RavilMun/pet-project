package ru.ravil.petproject.ai;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.List;
import ru.ravil.petproject.domain.MemoryUnitType;

public record AiMemoryUnitResult(
        MemoryUnitType type,
        String title,
        String summary,
        Set<String> tags,
        boolean actionable,
        double confidence,
        String sourceQuote,
        List<AiMemorySlotResult> slots,
        Map<String, Object> metadata,
        OffsetDateTime occurredAt,
        OffsetDateTime dueAt
) {
}
