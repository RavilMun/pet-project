package ru.ravil.petproject.service;

import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.dto.MemorySlotResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;

@Component
public class MemoryUnitMapper {

    public MemoryUnitResponse toResponse(MemoryUnit unit) {
        InboxItem item = unit.getItem();
        return new MemoryUnitResponse(
                unit.getId(),
                item == null ? null : item.getId(),
                item == null ? null : item.getRawText(),
                unit.getTitle(),
                unit.getSummary(),
                unit.getType(),
                new LinkedHashSet<>(unit.getTags()),
                unit.getSlots().stream()
                        .map(slot -> new MemorySlotResponse(
                                slot.getRole(),
                                slot.getValue(),
                                slot.getNormalizedValue(),
                                slot.getValueType(),
                                slot.getConfidence()
                        ))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                unit.isActionable(),
                unit.getConfidence(),
                unit.getSourceQuote(),
                unit.getOccurredAt(),
                unit.getDueAt(),
                item == null ? null : item.getCreatedAt(),
                unit.getCreatedAt(),
                unit.getUpdatedAt()
        );
    }
}
