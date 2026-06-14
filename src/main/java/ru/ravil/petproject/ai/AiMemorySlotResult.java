package ru.ravil.petproject.ai;

import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemorySlotValueType;

public record AiMemorySlotResult(
        MemorySlotRole role,
        String value,
        String normalizedValue,
        MemorySlotValueType valueType,
        double confidence
) {
}
