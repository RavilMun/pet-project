package ru.ravil.petproject.dto;

import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemorySlotValueType;

public record MemorySlotResponse(
        MemorySlotRole role,
        String value,
        String normalizedValue,
        MemorySlotValueType valueType,
        double confidence
) {
}
